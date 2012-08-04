/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.ofbiz.service.job;

import java.io.IOException;
import java.sql.Timestamp;
import com.ibm.icu.util.Calendar;
import java.util.Date;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;

import javolution.util.FastMap;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.service.calendar.TemporalExpression;
import org.ofbiz.service.calendar.TemporalExpressionWorker;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityFieldMap;
import org.ofbiz.entity.serialize.SerializeException;
import org.ofbiz.entity.serialize.XmlSerializer;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericRequester;
import org.ofbiz.service.ServiceUtil;
import org.ofbiz.service.calendar.RecurrenceInfo;
import org.ofbiz.service.config.ServiceConfigUtil;
import org.xml.sax.SAXException;

import org.apache.commons.lang.StringUtils;

/**
 * A {@link Job} that is backed by the entity engine. Job data is stored
 * in the JobSandbox entity.
 */
@SuppressWarnings("serial")
public class PersistedServiceJob extends GenericServiceJob {

    public static final String module = PersistedServiceJob.class.getName();

    private transient Delegator delegator = null;
    private long nextRecurrence = -1;
    private long maxRetry = -1;
    private long currentRetryCount = 0;
    private boolean warningLogged = false;

    /**
     * Creates a new PersistedServiceJob
     * @param dctx
     * @param jobValue
     * @param req
     */
    public PersistedServiceJob(DispatchContext dctx, GenericValue jobValue, GenericRequester req) {
        super(dctx, jobValue.getString("jobId"), jobValue.getString("jobName"), null, null, req);
        this.delegator = dctx.getDelegator();
        Timestamp storedDate = jobValue.getTimestamp("runTime");
        this.runtime = storedDate.getTime();
        this.maxRetry = jobValue.get("maxRetry") != null ? jobValue.getLong("maxRetry").longValue() : -1;
        Long retryCount = jobValue.getLong("currentRetryCount");
        if (retryCount != null) {
            this.currentRetryCount = retryCount.longValue();
        } else {
            // backward compatibility
            this.currentRetryCount = PersistedServiceJob.getRetries(jobValue, this.delegator);
        }
    }

    @Override
    public void queue() throws InvalidJobException {
        super.queue();
        // refresh the job object
        GenericValue jobValue = null;
        try {
            jobValue = this.getJob();
            jobValue.refresh();
        } catch (GenericEntityException e) {
            throw new InvalidJobException("Unable to refresh JobSandbox value", e);
        }
        Timestamp cancelTime = jobValue.getTimestamp("cancelDateTime");
        Timestamp startTime = jobValue.getTimestamp("startDateTime");
        if (cancelTime != null || startTime != null) {
            // job not available
            throw new InvalidJobException("Job [" + getJobId() + "] is not available");
        } else {
            // set the start time to now
            jobValue.set("startDateTime", UtilDateTime.nowTimestamp());
            jobValue.set("statusId", "SERVICE_RUNNING");
            try {
                jobValue.store();
            } catch (GenericEntityException e) {
                throw new InvalidJobException("Unable to set the startDateTime on the current job [" + getJobId() + "]; not running!", e);
            }
        }
    }

    @Override
    protected void init() throws InvalidJobException {
        super.init();

        // configure any addition recurrences
        GenericValue job = this.getJob();
        long maxRecurrenceCount = -1;
        long currentRecurrenceCount = 0;
        TemporalExpression expr = null;
        RecurrenceInfo recurrence = JobManager.getRecurrenceInfo(job);
        if (recurrence != null) {
            if (!this.warningLogged) {
                Debug.logWarning("Persisted Job [" + getJobId() + "] references a RecurrenceInfo, recommend using TemporalExpression instead", module);
                this.warningLogged = true;
            }
            currentRecurrenceCount = recurrence.getCurrentCount();
            expr = RecurrenceInfo.toTemporalExpression(recurrence);
        }
        if (expr == null && UtilValidate.isNotEmpty(job.getString("tempExprId"))) {
            try {
                expr = TemporalExpressionWorker.getTemporalExpression(this.delegator, job.getString("tempExprId"));
            } catch (GenericEntityException e) {
                throw new RuntimeException(e.getMessage());
            }
        }

        String instanceId = UtilProperties.getPropertyValue("general.properties", "unique.instanceId", "ofbiz0");
        if (!instanceId.equals(job.getString("runByInstanceId"))) {
            throw new InvalidJobException("Job has been accepted by a different instance!");
        }

        if (job.get("maxRecurrenceCount") != null) {
            maxRecurrenceCount = job.getLong("maxRecurrenceCount").longValue();
        }
        if (job.get("currentRecurrenceCount") != null) {
            currentRecurrenceCount = job.getLong("currentRecurrenceCount").longValue();
        }
        if (maxRecurrenceCount != -1) {
            currentRecurrenceCount++;
            job.set("currentRecurrenceCount", currentRecurrenceCount);
        }

        try {
            if (expr != null && (maxRecurrenceCount == -1 || currentRecurrenceCount <= maxRecurrenceCount)) {
                if (recurrence != null) {
                    recurrence.incrementCurrentCount();
                }
                Calendar next = expr.next(Calendar.getInstance());
                if (next != null) {
                    createRecurrence(job, next.getTimeInMillis(), false);
                }
            }
        } catch (GenericEntityException e) {
            throw new RuntimeException(e.getMessage());
        }
        if (Debug.infoOn()) Debug.logInfo("Job  [" + getJobName() + "] Id ["  + getJobId() + "] -- Next runtime: " + new Date(nextRecurrence), module);
    }

    private void createRecurrence(GenericValue job, long next, boolean isRetryOnFailure) throws GenericEntityException {
        if (Debug.verboseOn()) Debug.logVerbose("Next runtime returned: " + next, module);

        if (next > runtime) {
            String pJobId = job.getString("parentJobId");
            if (pJobId == null) {
                pJobId = job.getString("jobId");
            }
            GenericValue newJob = GenericValue.create(job);
            newJob.remove("jobId");
            newJob.set("previousJobId", job.getString("jobId"));
            newJob.set("parentJobId", pJobId);
            newJob.set("statusId", "SERVICE_PENDING");
            newJob.set("startDateTime", null);
            newJob.set("runByInstanceId", null);
            newJob.set("runTime", new java.sql.Timestamp(next));
            if (isRetryOnFailure) {
                newJob.set("currentRetryCount", new Long(currentRetryCount + 1));
            } else {
                newJob.set("currentRetryCount", new Long(0));
            }
            nextRecurrence = next;
            delegator.createSetNextSeqId(newJob);
            if (Debug.verboseOn()) Debug.logVerbose("Created next job entry: " + newJob, module);
        }
    }

    @Override
    protected void finish(Map<String, Object> result) throws InvalidJobException {
        super.finish(result);

        // set the finish date
        GenericValue job = getJob();
        job.set("statusId", "SERVICE_FINISHED");
        job.set("finishDateTime", UtilDateTime.nowTimestamp());
        String jobResult = null;
        if (ServiceUtil.isError(result)) {
            jobResult = StringUtils.substring(ServiceUtil.getErrorMessage(result), 0, 255);
        } else {
            jobResult = StringUtils.substring(ServiceUtil.makeSuccessMessage(result, "", "", "", ""), 0, 255);
        }
        if (UtilValidate.isNotEmpty(jobResult)) {
            job.set("jobResult", jobResult);
        }
        try {
            job.store();
        } catch (GenericEntityException e) {
            Debug.logError(e, "Cannot update the job [" + getJobId() + "] sandbox", module);
        }
    }

    @Override
    protected void failed(Throwable t) throws InvalidJobException {
        super.failed(t);

        GenericValue job = getJob();
        // if the job has not been re-scheduled; we need to re-schedule and run again
        if (nextRecurrence == -1) {
            if (this.canRetry()) {
                // create a recurrence
                Calendar cal = Calendar.getInstance();
                cal.setTime(new Date());
                cal.add(Calendar.MINUTE, ServiceConfigUtil.getFailedRetryMin());
                long next = cal.getTimeInMillis();
                try {
                    createRecurrence(job, next, true);
                } catch (GenericEntityException gee) {
                    Debug.logError(gee, "ERROR: Unable to re-schedule job [" + getJobId() + "] to re-run : " + job, module);
                }
                Debug.logInfo("Persisted Job [" + getJobId() + "] Failed Re-Scheduling : " + next, module);
            } else {
                Debug.logWarning("Persisted Job [" + getJobId() + "] Failed - Max Retry Hit; not re-scheduling", module);
            }
        }
        // set the failed status
        job.set("statusId", "SERVICE_FAILED");
        job.set("finishDateTime", UtilDateTime.nowTimestamp());
        job.set("jobResult", StringUtils.substring(t.getMessage(), 0, 255));
        try {
            job.store();
        } catch (GenericEntityException e) {
            Debug.logError(e, "Cannot update the job sandbox", module);
        }
    }

    @Override
    protected String getServiceName() throws InvalidJobException {
        GenericValue jobObj = getJob();
        if (jobObj == null || jobObj.get("serviceName") == null) {
            return null;
        }
        return jobObj.getString("serviceName");
    }

    @Override
    protected Map<String, Object> getContext() throws InvalidJobException {
        Map<String, Object> context = null;
        try {
            GenericValue jobObj = getJob();
            if (!UtilValidate.isEmpty(jobObj.getString("runtimeDataId"))) {
                GenericValue contextObj = jobObj.getRelatedOne("RuntimeData", false);
                if (contextObj != null) {
                    context = UtilGenerics.checkMap(XmlSerializer.deserialize(contextObj.getString("runtimeInfo"), delegator), String.class, Object.class);
                }
            }

            if (context == null) {
                context = FastMap.newInstance();
            }

            // check the runAsUser
            if (!UtilValidate.isEmpty(jobObj.get("runAsUser"))) {
                context.put("userLogin", ServiceUtil.getUserLogin(dctx, context, jobObj.getString("runAsUser")));
            }
        } catch (GenericEntityException e) {
            Debug.logError(e, "PersistedServiceJob.getContext(): Entity Exception", module);
        } catch (SerializeException e) {
            Debug.logError(e, "PersistedServiceJob.getContext(): Serialize Exception", module);
        } catch (ParserConfigurationException e) {
            Debug.logError(e, "PersistedServiceJob.getContext(): Parse Exception", module);
        } catch (SAXException e) {
            Debug.logError(e, "PersistedServiceJob.getContext(): SAXException", module);
        } catch (IOException e) {
            Debug.logError(e, "PersistedServiceJob.getContext(): IOException", module);
        }
        if (context == null) {
            Debug.logError("Job context is null", module);
        }

        return context;
    }

    // gets the job value object
    private GenericValue getJob() throws InvalidJobException {
        try {
            GenericValue jobObj = delegator.findOne("JobSandbox", false, "jobId", getJobId());
            if (jobObj == null) {
                throw new InvalidJobException("Job [" + getJobId() + "] came back null from datasource from delegator " + delegator.getDelegatorName());
            }
            return jobObj;
        } catch (GenericEntityException e) {
            throw new InvalidJobException("Cannot get job definition [" + getJobId() + "] from entity", e);
        }
    }

    // returns the number of current retries
    private static long getRetries(GenericValue job, Delegator delegator) {
        String pJobId = job.getString("parentJobId");
        if (pJobId == null) {
            return 0;
        }

        long count = 0;
        try {
            EntityFieldMap ecl = EntityCondition.makeConditionMap("parentJobId", pJobId, "statusId", "SERVICE_FAILED");
            count = delegator.findCountByCondition("JobSandbox", ecl, null, null);
        } catch (GenericEntityException e) {
            Debug.logError(e, module);
        }

        return count + 1; // add one for the parent
    }

    private boolean canRetry() throws InvalidJobException {
        if (maxRetry == -1) {
            return true;
        }
        return currentRetryCount < maxRetry;
    }
}
