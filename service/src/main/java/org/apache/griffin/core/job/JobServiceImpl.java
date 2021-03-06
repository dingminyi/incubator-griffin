/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/

package org.apache.griffin.core.job;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.lang.StringUtils;
import org.apache.griffin.core.exception.GriffinException;
import org.apache.griffin.core.job.entity.*;
import org.apache.griffin.core.job.repo.GriffinJobRepo;
import org.apache.griffin.core.job.repo.JobInstanceRepo;
import org.apache.griffin.core.job.repo.JobScheduleRepo;
import org.apache.griffin.core.measure.entity.DataSource;
import org.apache.griffin.core.measure.entity.GriffinMeasure;
import org.apache.griffin.core.measure.repo.GriffinMeasureRepo;
import org.apache.griffin.core.util.JsonUtil;
import org.quartz.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.text.ParseException;
import java.util.*;

import static org.apache.griffin.core.exception.GriffinExceptionMessage.*;
import static org.quartz.JobBuilder.newJob;
import static org.quartz.JobKey.jobKey;
import static org.quartz.TriggerBuilder.newTrigger;
import static org.quartz.TriggerKey.triggerKey;

@Service
public class JobServiceImpl implements JobService {
    private static final Logger LOGGER = LoggerFactory.getLogger(JobServiceImpl.class);
    public static final String JOB_SCHEDULE_ID = "jobScheduleId";
    public static final String GRIFFIN_JOB_ID = "griffinJobId";
    static final int MAX_PAGE_SIZE = 1024;
    static final int DEFAULT_PAGE_SIZE = 10;

    @Autowired
    private SchedulerFactoryBean factory;
    @Autowired
    private JobInstanceRepo jobInstanceRepo;
    @Autowired
    @Qualifier("livyConf")
    private Properties livyConf;
    @Autowired
    private GriffinMeasureRepo measureRepo;
    @Autowired
    private GriffinJobRepo jobRepo;
    @Autowired
    private JobScheduleRepo jobScheduleRepo;

    private RestTemplate restTemplate;

    public JobServiceImpl() {
        restTemplate = new RestTemplate();
    }

    @Override
    public List<JobDataBean> getAliveJobs() {
        Scheduler scheduler = factory.getObject();
        List<JobDataBean> dataList = new ArrayList<>();
        try {
            List<GriffinJob> jobs = jobRepo.findByDeleted(false);
            for (GriffinJob job : jobs) {
                JobDataBean jobData = genJobData(scheduler, jobKey(job.getQuartzName(), job.getQuartzGroup()), job);
                if (jobData != null) {
                    dataList.add(jobData);
                }
            }
        } catch (SchedulerException e) {
            LOGGER.error("Failed to get running jobs.", e);
            throw new GriffinException.ServiceException("Failed to get running jobs.", e);
        }
        return dataList;
    }

    private JobDataBean genJobData(Scheduler scheduler, JobKey jobKey, GriffinJob job) throws SchedulerException {
        List<Trigger> triggers = (List<Trigger>) scheduler.getTriggersOfJob(jobKey);
        if (CollectionUtils.isEmpty(triggers)) {
            return null;
        }
        JobDataBean jobData = new JobDataBean();
        Trigger trigger = triggers.get(0);
        setTriggerTime(trigger, jobData);
        jobData.setJobId(job.getId());
        jobData.setJobName(job.getJobName());
        jobData.setMeasureId(job.getMeasureId());
        jobData.setTriggerState(scheduler.getTriggerState(trigger.getKey()));
        jobData.setCronExpression(getCronExpression(triggers));
        return jobData;
    }

    private String getCronExpression(List<Trigger> triggers) {
        for (Trigger trigger : triggers) {
            if (trigger instanceof CronTrigger) {
                return ((CronTrigger) trigger).getCronExpression();
            }
        }
        return null;
    }

    private void setTriggerTime(Trigger trigger, JobDataBean jobBean) {
        Date nextFireTime = trigger.getNextFireTime();
        Date previousFireTime = trigger.getPreviousFireTime();
        jobBean.setNextFireTime(nextFireTime != null ? nextFireTime.getTime() : -1);
        jobBean.setPreviousFireTime(previousFireTime != null ? previousFireTime.getTime() : -1);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GriffinJob addJob(JobSchedule js) throws Exception {
        Long measureId = js.getMeasureId();
        GriffinMeasure measure = getMeasureIfValid(measureId);
        checkJobScheduleParams(js, measure);
        String qName = getQuartzName(js);
        String qGroup = getQuartzGroupName();
        TriggerKey triggerKey = triggerKey(qName, qGroup);
        if (factory.getObject().checkExists(triggerKey)) {
            throw new GriffinException.ConflictException(QUARTZ_JOB_ALREADY_EXIST);
        }
        GriffinJob job = new GriffinJob(measure.getId(), js.getJobName(), qName, qGroup, false);
        job = jobRepo.save(job);
        js = jobScheduleRepo.save(js);
        addJob(triggerKey, js, job);
        return job;

    }

    private void addJob(TriggerKey triggerKey, JobSchedule js, GriffinJob job) throws Exception {
        Scheduler scheduler = factory.getObject();
        JobDetail jobDetail = addJobDetail(scheduler, triggerKey, js, job);
        scheduler.scheduleJob(genTriggerInstance(triggerKey, jobDetail, js));
    }

    private String getQuartzName(JobSchedule js) {
        return js.getJobName() + "_" + System.currentTimeMillis();
    }

    private String getQuartzGroupName() {
        return "BA";
    }

    private void checkJobScheduleParams(JobSchedule js, GriffinMeasure measure) {
        if (!isJobNameValid(js.getJobName())) {
            throw new GriffinException.BadRequestException(INVALID_JOB_NAME);
        }
        if (!isBaseLineValid(js.getSegments())) {
            throw new GriffinException.BadRequestException(MISSING_BASELINE_CONFIG);
        }
        List<String> names = getConnectorNames(measure);
        if (!isConnectorNamesValid(js.getSegments(), names)) {
            throw new GriffinException.BadRequestException(INVALID_CONNECTOR_NAME);
        }
    }

    private boolean isJobNameValid(String jobName) {
        if (StringUtils.isEmpty(jobName)) {
            LOGGER.warn("Job name cannot be empty.");
            return false;
        }
        int size = jobRepo.countByJobNameAndDeleted(jobName, false);
        if (size > 0) {
            LOGGER.warn("Job name already exits.");
            return false;
        }
        return true;
    }

    private boolean isBaseLineValid(List<JobDataSegment> segments) {
        for (JobDataSegment jds : segments) {
            if (jds.getBaseline()) {
                return true;
            }
        }
        LOGGER.warn("Please set segment timestamp baseline in as.baseline field.");
        return false;
    }

    private boolean isConnectorNamesValid(List<JobDataSegment> segments, List<String> names) {
        Set<String> dcSets = new HashSet<>();
        for (JobDataSegment segment : segments) {
            String dcName = segment.getDataConnectorName();
            dcSets.add(dcName);
            if (!isConnectorNameValid(dcName, names)) {
                return false;
            }
        }
        if (dcSets.size() < segments.size()) {
            LOGGER.warn("Connector names in job data segment cannot be repeated.");
            return false;
        }
        return true;
    }

    private boolean isConnectorNameValid(String param, List<String> names) {
        for (String name : names) {
            if (name.equals(param)) {
                return true;
            }
        }
        LOGGER.warn("Param {} is a illegal string. Please input one of strings in {}.", param, names);
        return false;
    }

    private List<String> getConnectorNames(GriffinMeasure measure) {
        List<String> names = new ArrayList<>();
        Set<String> sets = new HashSet<>();
        List<DataSource> sources = measure.getDataSources();
        for (DataSource source : sources) {
            source.getConnectors().forEach(dc -> sets.add(dc.getName()));
        }
        if (sets.size() < sources.size()) {
            LOGGER.warn("Connector names cannot be repeated.");
            return Collections.emptyList();
        }
        names.addAll(sets);
        return names;
    }

    private GriffinMeasure getMeasureIfValid(Long measureId) {
        GriffinMeasure measure = measureRepo.findByIdAndDeleted(measureId, false);
        if (measure == null) {
            LOGGER.warn("The measure id {} isn't valid. Maybe it doesn't exist or is external measure type.", measureId);
            throw new GriffinException.BadRequestException(INVALID_MEASURE_ID);
        }
        return measure;
    }


    private Trigger genTriggerInstance(TriggerKey triggerKey, JobDetail jd, JobSchedule js) throws ParseException {
        return newTrigger()
                .withIdentity(triggerKey)
                .forJob(jd)
                .withSchedule(CronScheduleBuilder.cronSchedule(new CronExpression(js.getCronExpression()))
                        .inTimeZone(TimeZone.getTimeZone(js.getTimeZone()))
                )
                .build();
    }

    private JobDetail addJobDetail(Scheduler scheduler, TriggerKey triggerKey, JobSchedule js, GriffinJob job) throws SchedulerException {
        JobKey jobKey = jobKey(triggerKey.getName(), triggerKey.getGroup());
        JobDetail jobDetail;
        Boolean isJobKeyExist = scheduler.checkExists(jobKey);
        if (isJobKeyExist) {
            jobDetail = scheduler.getJobDetail(jobKey);
        } else {
            jobDetail = newJob(JobInstance.class).storeDurably().withIdentity(jobKey).build();
        }
        setJobDataMap(jobDetail, js, job);
        scheduler.addJob(jobDetail, isJobKeyExist);
        return jobDetail;
    }


    private void setJobDataMap(JobDetail jd, JobSchedule js, GriffinJob job) {
        JobDataMap jobDataMap = jd.getJobDataMap();
        jobDataMap.put(JOB_SCHEDULE_ID, js.getId().toString());
        jobDataMap.put(GRIFFIN_JOB_ID, job.getId().toString());
    }

    private boolean pauseJob(List<JobInstanceBean> instances) {
        if (CollectionUtils.isEmpty(instances)) {
            return true;
        }
        List<JobInstanceBean> deletedInstances = new ArrayList<>();
        boolean pauseStatus = true;
        for (JobInstanceBean instance : instances) {
            boolean status = pauseJob(instance, deletedInstances);
            pauseStatus = pauseStatus && status;
        }
        jobInstanceRepo.save(deletedInstances);
        return pauseStatus;
    }

    private boolean pauseJob(JobInstanceBean instance, List<JobInstanceBean> deletedInstances) {
        boolean status = true;
        try {
            pauseJob(instance.getPredicateGroup(), instance.getPredicateName());
            instance.setDeleted(true);
            deletedInstances.add(instance);
        } catch (SchedulerException e) {
            LOGGER.error("Failed to pause predicate job({},{}).", instance.getId(), instance.getPredicateName());
            status = false;
        }
        return status;
    }

    @Override
    public void pauseJob(String group, String name) throws SchedulerException {
        Scheduler scheduler = factory.getObject();
        JobKey jobKey = new JobKey(name, group);
        if (!scheduler.checkExists(jobKey)) {
            LOGGER.warn("Job({},{}) does not exist.", jobKey.getGroup(), jobKey.getName());
            return;
        }
        scheduler.pauseJob(jobKey);
    }

    private void setJobDeleted(GriffinJob job) {
        job.setDeleted(true);
        jobRepo.save(job);
    }

    private void deletePredicateJob(GriffinJob job) throws SchedulerException {
        List<JobInstanceBean> instances = job.getJobInstances();
        for (JobInstanceBean instance : instances) {
            if (!instance.getDeleted()) {
                deleteJob(instance.getPredicateGroup(), instance.getPredicateName());
                instance.setDeleted(true);
                if (instance.getState().equals(LivySessionStates.State.finding)) {
                    instance.setState(LivySessionStates.State.not_found);
                }
            }
        }
    }

    /**
     * logically delete
     * 1. pause these jobs
     * 2. set these jobs as deleted status
     *
     * @param jobId griffin job id
     */
    @Override
    public void deleteJob(Long jobId) {
        GriffinJob job = jobRepo.findByIdAndDeleted(jobId, false);
        if (job == null) {
            LOGGER.warn("Griffin job does not exist.");
            throw new GriffinException.NotFoundException(JOB_ID_DOES_NOT_EXIST);
        }
        deleteJob(job);
    }

    /**
     * logically delete
     *
     * @param name griffin job name which may not be unique.
     */
    @Override
    public void deleteJob(String name) {
        List<GriffinJob> jobs = jobRepo.findByJobNameAndDeleted(name, false);
        if (CollectionUtils.isEmpty(jobs)) {
            LOGGER.warn("There is no job with '{}' name.", name);
            throw new GriffinException.NotFoundException(JOB_NAME_DOES_NOT_EXIST);
        }
        for (GriffinJob job : jobs) {
            deleteJob(job);
        }
    }

    private void deleteJob(GriffinJob job) {
        try {
            pauseJob(job.getQuartzGroup(), job.getQuartzName());
            deletePredicateJob(job);
            setJobDeleted(job);
        } catch (Exception e) {
            LOGGER.error("Failed to delete job", e);
            throw new GriffinException.ServiceException("Failed to delete job", e);
        }
    }

    private void deleteJob(String group, String name) throws SchedulerException {
        Scheduler scheduler = factory.getObject();
        JobKey jobKey = new JobKey(name, group);
        if (!scheduler.checkExists(jobKey)) {
            LOGGER.info("Job({},{}) does not exist.", jobKey.getGroup(), jobKey.getName());
            return;
        }
        scheduler.deleteJob(jobKey);
    }

    /**
     * deleteJobsRelateToMeasure
     * 1. search jobs related to measure
     * 2. deleteJob
     *
     * @param measureId measure id
     */
    public void deleteJobsRelateToMeasure(Long measureId) {
        List<GriffinJob> jobs = jobRepo.findByMeasureIdAndDeleted(measureId, false);
        if (CollectionUtils.isEmpty(jobs)) {
            LOGGER.info("Measure id {} has no related jobs.", measureId);
            return;
        }
        for (GriffinJob job : jobs) {
            deleteJob(job);
        }
    }

    @Override
    public List<JobInstanceBean> findInstancesOfJob(Long jobId, int page, int size) {
        AbstractJob job = jobRepo.findByIdAndDeleted(jobId, false);
        if (job == null) {
            LOGGER.warn("Job id {} does not exist.", jobId);
            throw new GriffinException.NotFoundException(JOB_ID_DOES_NOT_EXIST);
        }
        size = size > MAX_PAGE_SIZE ? MAX_PAGE_SIZE : size;
        size = size <= 0 ? DEFAULT_PAGE_SIZE : size;
        Pageable pageable = new PageRequest(page, size, Sort.Direction.DESC, "tms");
        return jobInstanceRepo.findByJobId(jobId, pageable);
    }

    @Scheduled(fixedDelayString = "${jobInstance.expired.milliseconds}")
    public void deleteExpiredJobInstance() {
        Long timeMills = System.currentTimeMillis();
        List<JobInstanceBean> instances = jobInstanceRepo.findByExpireTmsLessThanEqual(timeMills);
        if (!pauseJob(instances)) {
            LOGGER.error("Pause job failure.");
            return;
        }
        jobInstanceRepo.deleteByExpireTimestamp(timeMills);
        LOGGER.info("Delete expired job instances success.");
    }

    @Scheduled(fixedDelayString = "${jobInstance.fixedDelay.in.milliseconds}")
    public void syncInstancesOfAllJobs() {
        List<JobInstanceBean> beans = jobInstanceRepo.findByActiveState();
        if (!CollectionUtils.isEmpty(beans)) {
            for (JobInstanceBean jobInstance : beans) {
                syncInstancesOfJob(jobInstance);
            }
        }
    }


    /**
     * call livy to update part of job instance table data associated with group and jobName in mysql.
     *
     * @param jobInstance job instance livy info
     */
    private void syncInstancesOfJob(JobInstanceBean jobInstance) {
        String uri = livyConf.getProperty("livy.uri") + "/" + jobInstance.getSessionId();
        TypeReference<HashMap<String, Object>> type = new TypeReference<HashMap<String, Object>>() {
        };
        try {
            String resultStr = restTemplate.getForObject(uri, String.class);
            HashMap<String, Object> resultMap = JsonUtil.toEntity(resultStr, type);
            setJobInstanceIdAndUri(jobInstance, resultMap);
        } catch (RestClientException e) {
            LOGGER.error("Spark session {} has overdue, set state as unknown!\n {}", jobInstance.getSessionId(), e.getMessage());
            setJobInstanceUnknownStatus(jobInstance);
        } catch (IOException e) {
            LOGGER.error("Job instance json converts to map failed. {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            LOGGER.error("Livy status is illegal. {}", e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Sync job instances failure. {}", e.getMessage());
        }
    }


    private void setJobInstanceIdAndUri(JobInstanceBean instance, HashMap<String, Object> resultMap) {
        if (resultMap != null && resultMap.size() != 0 && resultMap.get("state") != null) {
            instance.setState(LivySessionStates.State.valueOf(resultMap.get("state").toString()));
            if (resultMap.get("appId") != null) {
                String appId = String.valueOf(resultMap.get("appId"));
                String appUri = livyConf.getProperty("spark.uri") + "/cluster/app/" + appId;
                instance.setAppId(appId);
                instance.setAppUri(appUri);
            }
            jobInstanceRepo.save(instance);

        }

    }

    private void setJobInstanceUnknownStatus(JobInstanceBean jobInstance) {
        //if server cannot get session from Livy, set State as unknown.
        jobInstance.setState(LivySessionStates.State.unknown);
        jobInstanceRepo.save(jobInstance);
    }

    /**
     * a job is regard as healthy job when its latest instance is in healthy state.
     *
     * @return job healthy statistics
     */
    @Override
    public JobHealth getHealthInfo() {
        JobHealth jobHealth = new JobHealth();
        List<GriffinJob> jobs = jobRepo.findByDeleted(false);
        for (GriffinJob job : jobs) {
            jobHealth = getHealthInfo(jobHealth, job);
        }
        return jobHealth;
    }

    private JobHealth getHealthInfo(JobHealth jobHealth, GriffinJob job) {
        List<Trigger> triggers = getTriggers(job);
        if (!CollectionUtils.isEmpty(triggers)) {
            jobHealth.setJobCount(jobHealth.getJobCount() + 1);
            if (isJobHealthy(job.getId())) {
                jobHealth.setHealthyJobCount(jobHealth.getHealthyJobCount() + 1);
            }
        }
        return jobHealth;
    }

    private List<Trigger> getTriggers(GriffinJob job) {
        JobKey jobKey = new JobKey(job.getQuartzName(), job.getQuartzGroup());
        List<Trigger> triggers;
        try {
            triggers = (List<Trigger>) factory.getObject().getTriggersOfJob(jobKey);
        } catch (SchedulerException e) {
            LOGGER.error("Job schedule exception. {}", e.getMessage());
            throw new GriffinException.ServiceException("Fail to Get HealthInfo", e);
        }
        return triggers;
    }

    private Boolean isJobHealthy(Long jobId) {
        Pageable pageable = new PageRequest(0, 1, Sort.Direction.DESC, "tms");
        List<JobInstanceBean> instances = jobInstanceRepo.findByJobId(jobId, pageable);
        return !CollectionUtils.isEmpty(instances) && LivySessionStates.isHealthy(instances.get(0).getState());
    }


}
