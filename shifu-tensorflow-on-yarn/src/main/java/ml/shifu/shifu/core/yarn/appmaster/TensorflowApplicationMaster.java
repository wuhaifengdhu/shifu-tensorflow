/*
 * Copyright [2013-2018] PayPal Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ml.shifu.shifu.core.yarn.appmaster;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.client.api.async.AMRMClientAsync;
import org.apache.hadoop.yarn.client.api.async.impl.NMClientAsyncImpl;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.util.AbstractLivelinessMonitor;
import org.apache.hadoop.yarn.util.ConverterUtils;

import ml.shifu.shifu.core.yarn.util.CommonUtils;
import ml.shifu.shifu.core.yarn.util.Constants;
import ml.shifu.shifu.core.yarn.util.GlobalConfigurationKeys;
import ml.shifu.shifu.util.HDFSUtils;

/**
 * @author webai
 *
 */
public class TensorflowApplicationMaster extends AbstractApplicationMaster{
    private static final Log LOG = LogFactory.getLog(TensorflowApplicationMaster.class);

    /** HeartBeat monitor **/
    private final AbstractLivelinessMonitor<TensorflowTask> hbMonitor;
    private int hbInterval;
    private int maxConsecutiveHBMiss;
    private volatile boolean taskHasMissesHB = false;
    private Thread mainThread;

    /** Configuration **/
    private YarnConfiguration yarnConf = new YarnConfiguration();
    private Configuration globalConf = new Configuration();
    FileSystem hdfs = HDFSUtils.getFS();

    /** Tensorflow session **/
    private TensorflowSession session; // Create a dummy session for single node training.

    /** The environment set up for the TaskExecutor **/
    private Map<String, String> containerEnv = new ConcurrentHashMap<String, String>();
    
    private int appTimeout;
    private long workerTimeout;
    private ContainerId containerId;
    private String appIdString;

    private TensorflowApplicationMaster() {
        hbMonitor = new AbstractLivelinessMonitor<TensorflowTask>("Tensorflow Task liveliness Monitor",
                new MonotonicClock()) {
            @Override
            protected void expire(TensorflowTask task) {
                onTaskDeemedDead(task);
            }

            @Override
            protected void serviceStart() throws Exception {
                setMonitorInterval(hbInterval * 3);
                setExpireInterval(hbInterval * Math.max(3, maxConsecutiveHBMiss)); // Be at least == monitoring interval
                super.serviceStart();
            }
            
            private void onTaskDeemedDead(TensorflowTask task) {
                LOG.info("Task with id [" + task.getId() + "] has missed" + " [" + maxConsecutiveHBMiss
                        + "] heartbeats.. Ending application !!");
                // TODO: figure out what is the right thing to do here..
                // TODO: For the time being, we just kill the job..
                String msg = "Task with id [" + task.getId() + "] deemed dead!!";
                LOG.error(msg);
                taskHasMissesHB = true;
                //session.setFinalStatus(FinalApplicationStatus.FAILED, msg);
                //mainThread.interrupt();
            }
        };
    }

    /**
     * Entry point of TensorflowApplicationMaster
     * The workflow of a training job in AM
     * 
     * @param args
     *            the args from user inputs
     */
    public static void main(String[] args) {
        TensorflowApplicationMaster am = new TensorflowApplicationMaster();
        try {
            am.run(args);
            
            LOG.info("Application Master completed successfully. Exiting");
            System.exit(0);
        } catch (Exception e) {
            LOG.error("Fail to execute Tensorflow application master");
            System.exit(-1);
        }
    }

    /* 
     * Parse command line options and initialize TensorflowApplicationMaster
     */
    @Override
    protected void init(String[] args) {
        // retrieve information from args
        try {
            Options opts = new Options();
            opts.addOption("container_env", true, "");
            CommandLine cliParser = new GnuParser().parse(opts, args);
            containerEnv.putAll(CommonUtils.parseKeyValue(cliParser.getOptionValues("container_env")));
            
            //TODO parsing columnconfig file to pick out selected column number
            
        } catch (ParseException e) {
            throw new IllegalStateException("Parsing app master arguments fails", e); 
        }

        // retrieve information from environment
        Map<String, String> envs = System.getenv();
        containerId = ConverterUtils.toContainerId(envs.get(ApplicationConstants.Environment.CONTAINER_ID.name()));
        appIdString = containerId.getApplicationAttemptId().getApplicationId().toString();
        
        // retrieve information from global config
        globalConf.addResource(new Path(Constants.GLOBAL_FINAL_XML));
        appTimeout = globalConf.getInt(GlobalConfigurationKeys.APPLICATION_TIMEOUT,
                GlobalConfigurationKeys.DEFAULT_APPLICATION_TIMEOUT);
        workerTimeout = globalConf.getInt(GlobalConfigurationKeys.WORKER_TIMEOUT,
                GlobalConfigurationKeys.DEFAULT_WORKER_TIMEOUT);
        hbInterval = globalConf.getInt(GlobalConfigurationKeys.TASK_HEARTBEAT_INTERVAL_MS,
                GlobalConfigurationKeys.DEFAULT_TASK_HEARTBEAT_INTERVAL_MS);
        maxConsecutiveHBMiss = globalConf.getInt(GlobalConfigurationKeys.TASK_MAX_MISSED_HEARTBEATS,
                GlobalConfigurationKeys.DEFAULT_TASK_MAX_MISSED_HEARTBEATS);
        
        hbMonitor.init(globalConf);
        session = new TensorflowSession(globalConf);
        mainThread = Thread.currentThread();
    }

    /* (non-Javadoc)
     * @see ml.shifu.shifu.core.yarn.appmaster.AbstractApplicationMaster#registerRMCallbackHandler()
     */
    @Override
    protected void registerRMCallbackHandler() {
        // Init AMRMClient
        AMRMCallbackHandler allocListener = new AMRMCallbackHandler(this.globalConf, this.session,
                this.nmClientAsync, this.hbMonitor, this.containerEnv, appIdString);
        amRMClient = AMRMClientAsync.createAMRMClientAsync(1000, allocListener);
        amRMClient.init(yarnConf);
        amRMClient.start();
    }

    /* 
     * Register RM callback and start listening
     */
    @Override
    protected void registerNMCallbackHandler() {
        NMCallbackHandler containerListener = new NMCallbackHandler();
        nmClientAsync = new NMClientAsyncImpl(containerListener);
        nmClientAsync.init(yarnConf);
        nmClientAsync.start();
    }

    @Override
    protected void prepareBeforeTaskExector() {
        hbMonitor.start();
    }
    
    /* (non-Javadoc)
     * @see ml.shifu.shifu.core.yarn.appmaster.AbstractApplicationMaster#scheduleTask()
     */
    @Override
    protected void scheduleTask() {
        session.scheduleTasks(amRMClient); 
    }

    /* 
     * Monitor the TensorFlow training job.
     * 
     * @return if the tensorflow job finishes successfully.
     */
    @Override
    protected boolean monitor() {
        long expireTime = appTimeout == 0 ? Long.MAX_VALUE : System.currentTimeMillis() + appTimeout;
        int counter = 0;
        while(true) {
            counter += 1;
            // Checking timeout
            if(System.currentTimeMillis() > expireTime) {
                LOG.error("Application times out.");
                break;
            }

            if(!session.isChiefWorkerSuccess()) {
                LOG.info("Chief Worker exist with non-zero exit code. Training has finished.");
                break;
            }

            if(taskHasMissesHB) {
                LOG.info("Application failed due to missed heartbeats");
                break;
            }

            if (session.getFailedWorkers().size() > 0) {
                LOG.info("Some workers fails");
                break;
            }
            
            if (session.getFailedPs().size() > 0) {
                LOG.info("Some PS fails, could not continue...");
                break;
            }
            
            if(session.isChiefWorkerComplete()) {
                LOG.info("Chief worker complete and success, so training process is over...");
                return true;
            }
            
            // we are not going to use this condition to judge training finish or not
            //  because when chief worker finish, training would be finished. 
            //  after then, the other worker will not change model anymore
            /** TODO, remove
            if (session.getNumCompletedWorkerTasks().get() == this.session.getNumTotalWorkerTasks()) {
                // success
                CommonUtils.printWorkerTasksCompleted(this.session.getNumCompletedWorkerTasks(),
                        this.session.getNumTotalWorkerTasks());
                return true;
            }

            // Reduce logging frequency to every 100s.
            if(counter % 20 == 1) {
                CommonUtils.printWorkerTasksCompleted(this.session.getNumCompletedWorkerTasks(),
                        this.session.getNumTotalWorkerTasks());
            }
             **/
            // Pause before refresh job status
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                LOG.error("Monitor: Thread interrupted", e);
            }
        }

        return false;
    }
    
    @Override
    protected boolean canRecovered() {
        // is chief worker failed, whole jobs cannot continue
        if (!session.isChiefWorkerSuccess()) {
            return false;
        }
        
        // if any ps fails, cannot recover
        if (session.getFailedPs().size() > 0) {
            return false;
        }
        
        // if worker failed number bigger than left backup worker plus tolerance
        if (session.getFailedWorkers().size() >= session.failedWorkerMaxLimit()) {
            return false;
        }
            
        return true;
    }
    
    @Override
    protected void recovery() {
        // we do not need to recover ps because backup ps and ps are same
        
        ConcurrentLinkedQueue<Integer> workerFailedQueue = session.getFailedWorkers();
        ConcurrentLinkedQueue<TensorflowTask> backupWorkerQueue = session.getJobNameToBackupTask()
                .get(Constants.WORKER_JOB_NAME);
        while(!workerFailedQueue.isEmpty() && !backupWorkerQueue.isEmpty()) {
            TensorflowTask backupWorkerTask = backupWorkerQueue.poll();
            Integer failedWorkerTaskArrayId = workerFailedQueue.poll();
            
            try {
                session.weakupBackup(backupWorkerTask, failedWorkerTaskArrayId);
            } catch (Exception e) {
                LOG.error("error to write zookeeper", e);
            }
        }
    }    
    @Override
    protected void updateTaskStatus() {
        session.updateSessionStatus();

        CommonUtils.printWorkerTasksCompleted(this.session.getNumCompletedWorkerTasks(),
                this.session.getNumTotalWorkerTasks());

        FinalApplicationStatus status = session.getFinalStatus();
        String appMessage = session.getFinalMessage();
        if(status != FinalApplicationStatus.SUCCEEDED) {
            LOG.info("tensorflow session failed: " + appMessage);
        } else {
            LOG.info("tensorflow session is successful");
        }
    }

    @Override
    protected void stop() {
        FinalApplicationStatus status = session.getFinalStatus();
        String appMessage = session.getFinalMessage();
        try {
            amRMClient.unregisterApplicationMaster(status, appMessage, null);
        } catch (Exception ex) {
            LOG.error("Failed to unregister application", ex);
        }
        nmClientAsync.stop();
        amRMClient.waitForServiceToStop(5000);
        amRMClient.stop();

        // Pause before refresh job status
        try {
            Thread.sleep(30000);
        } catch (InterruptedException e) {
            LOG.error("stop: Thread interrupted", e);
        }
    }
}