/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.dangdang.ddframe.job.cloud.scheduler.restful;

import com.dangdang.ddframe.job.cloud.scheduler.config.app.CloudAppConfiguration;
import com.dangdang.ddframe.job.cloud.scheduler.config.app.CloudAppConfigurationGsonFactory;
import com.dangdang.ddframe.job.cloud.scheduler.config.app.CloudAppConfigurationService;
import com.dangdang.ddframe.job.cloud.scheduler.config.job.CloudJobConfiguration;
import com.dangdang.ddframe.job.cloud.scheduler.config.job.CloudJobConfigurationService;
import com.dangdang.ddframe.job.cloud.scheduler.mesos.MesosStateService;
import com.dangdang.ddframe.job.cloud.scheduler.mesos.MesosStateService.ExecutorStateInfo;
import com.dangdang.ddframe.job.cloud.scheduler.producer.ProducerManager;
import com.dangdang.ddframe.job.cloud.scheduler.state.disable.app.DisableAppService;
import com.dangdang.ddframe.job.exception.AppConfigurationException;
import com.dangdang.ddframe.job.exception.JobSystemException;
import com.dangdang.ddframe.job.reg.base.CoordinatorRegistryCenter;
import com.dangdang.ddframe.job.util.json.GsonFactory;
import com.google.common.base.Optional;
import org.apache.mesos.Protos.ExecutorID;
import org.apache.mesos.Protos.SlaveID;
import org.codehaus.jettison.json.JSONException;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Collection;

import static javax.ws.rs.core.Response.Status.NOT_FOUND;

/**
 * ??????????????????REST API.
 *
 * @author caohao
 */
@Path("/app")
public final class CloudAppRestfulApi {
    
    private static CoordinatorRegistryCenter regCenter;
    
    private static ProducerManager producerManager;
    
    private final CloudAppConfigurationService appConfigService;
    
    private final CloudJobConfigurationService jobConfigService;
    
    private final DisableAppService disableAppService;
    
    private final MesosStateService mesosStateService;
    
    public CloudAppRestfulApi() {
        appConfigService = new CloudAppConfigurationService(regCenter);
        jobConfigService = new CloudJobConfigurationService(regCenter);
        mesosStateService = new MesosStateService(regCenter);
        disableAppService = new DisableAppService(regCenter);
    }
    
    /**
     * ?????????.
     *
     * @param producerManager ???????????????
     * @param regCenter ????????????
     */
    public static void init(final CoordinatorRegistryCenter regCenter, final ProducerManager producerManager) {
        CloudAppRestfulApi.regCenter = regCenter;
        CloudAppRestfulApi.producerManager = producerManager;
        GsonFactory.registerTypeAdapter(CloudAppConfiguration.class, new CloudAppConfigurationGsonFactory.CloudAppConfigurationGsonTypeAdapter());
    }
    
    /**
     * ??????????????????.
     * 
     * @param appConfig ????????????
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public void register(final CloudAppConfiguration appConfig) {
        Optional<CloudAppConfiguration> appConfigFromZk = appConfigService.load(appConfig.getAppName());
        if (appConfigFromZk.isPresent()) {
            throw new AppConfigurationException("app '%s' already existed.", appConfig.getAppName());
        }
        appConfigService.add(appConfig);
    }
    
    /**
     * ??????????????????.
     *
     * @param appConfig ????????????
     */
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public void update(final CloudAppConfiguration appConfig) {
        appConfigService.update(appConfig);
    }
    
    /**
     * ??????????????????.
     *
     * @param appName ??????????????????
     * @return ????????????
     */
    @GET
    @Path("/{appName}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response detail(@PathParam("appName") final String appName) {
        Optional<CloudAppConfiguration> appConfig = appConfigService.load(appName);
        if (!appConfig.isPresent()) {
            return Response.status(NOT_FOUND).build();
        }
        return Response.ok(appConfig.get()).build();
    }
    
    /**
     * ????????????????????????.
     * 
     * @return ??????????????????
     */
    @GET
    @Path("/list")
    @Produces(MediaType.APPLICATION_JSON)
    public Collection<CloudAppConfiguration> findAllApps() {
        return appConfigService.loadAll();
    }
    
    /**
     * ???????????????????????????.
     * 
     * @param appName ????????????
     * @return ?????????????????????
     * @throws JSONException JSON????????????
     */
    @GET
    @Path("/{appName}/disable")
    @Produces(MediaType.APPLICATION_JSON)
    public boolean isDisabled(@PathParam("appName") final String appName) throws JSONException {
        return disableAppService.isDisabled(appName);
    }
    
    /**
     * ????????????.
     *
     * @param appName ????????????
     */
    @POST
    @Path("/{appName}/disable")
    public void disable(@PathParam("appName") final String appName) {
        if (appConfigService.load(appName).isPresent()) {
            disableAppService.add(appName);
            for (CloudJobConfiguration each : jobConfigService.loadAll()) {
                if (appName.equals(each.getAppName())) {
                    producerManager.unschedule(each.getJobName());
                }
            }
        }
    }
    
    /**
     * ????????????.
     * 
     * @param appName ????????????
     * @throws JSONException JSON????????????
     */
    @DELETE
    @Path("/{appName}/disable")
    public void enable(@PathParam("appName") final String appName) throws JSONException {
        if (appConfigService.load(appName).isPresent()) {
            disableAppService.remove(appName);
            for (CloudJobConfiguration each : jobConfigService.loadAll()) {
                if (appName.equals(each.getAppName())) {
                    producerManager.reschedule(each.getJobName());
                }
            }
        }
    }
    
    /**
     * ????????????.
     *
     * @param appName ????????????
     */
    @DELETE
    @Path("/{appName}")
    @Consumes(MediaType.APPLICATION_JSON)
    public void deregister(@PathParam("appName") final String appName) {
        if (appConfigService.load(appName).isPresent()) {
            removeAppAndJobConfigurations(appName);
            stopExecutors(appName);
        }
    }
    
    private void removeAppAndJobConfigurations(final String appName) {
        for (CloudJobConfiguration each : jobConfigService.loadAll()) {
            if (appName.equals(each.getAppName())) {
                producerManager.deregister(each.getJobName());
            }
        }
        disableAppService.remove(appName);
        appConfigService.remove(appName);
    }
    
    private void stopExecutors(final String appName) {
        try {
            Collection<ExecutorStateInfo> executorBriefInfo = mesosStateService.executors(appName);
            for (ExecutorStateInfo each : executorBriefInfo) {
                producerManager.sendFrameworkMessage(ExecutorID.newBuilder().setValue(each.getId()).build(),
                        SlaveID.newBuilder().setValue(each.getSlaveId()).build(), "STOP".getBytes());
            }
        } catch (final JSONException ex) {
            throw new JobSystemException(ex);
        }
    }
}
