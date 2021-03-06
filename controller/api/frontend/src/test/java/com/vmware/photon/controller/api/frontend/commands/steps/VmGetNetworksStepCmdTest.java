/*
 * Copyright 2015 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.vmware.photon.controller.api.frontend.commands.steps;

import com.vmware.photon.controller.api.frontend.backends.EntityLockBackend;
import com.vmware.photon.controller.api.frontend.backends.NetworkBackend;
import com.vmware.photon.controller.api.frontend.backends.StepBackend;
import com.vmware.photon.controller.api.frontend.backends.TaskBackend;
import com.vmware.photon.controller.api.frontend.backends.VmBackend;
import com.vmware.photon.controller.api.frontend.backends.clients.ApiFeXenonRestClient;
import com.vmware.photon.controller.api.frontend.backends.clients.DeployerClient;
import com.vmware.photon.controller.api.frontend.backends.clients.HousekeeperClient;
import com.vmware.photon.controller.api.frontend.backends.clients.PhotonControllerXenonRestClient;
import com.vmware.photon.controller.api.frontend.commands.tasks.TaskCommand;
import com.vmware.photon.controller.api.frontend.config.PaginationConfig;
import com.vmware.photon.controller.api.frontend.entities.StepEntity;
import com.vmware.photon.controller.api.frontend.entities.TaskEntity;
import com.vmware.photon.controller.api.frontend.entities.VmEntity;
import com.vmware.photon.controller.api.frontend.exceptions.external.UnsupportedOperationException;
import com.vmware.photon.controller.api.frontend.exceptions.internal.InternalException;
import com.vmware.photon.controller.api.frontend.utils.NetworkHelper;
import com.vmware.photon.controller.api.model.ResourceList;
import com.vmware.photon.controller.api.model.Subnet;
import com.vmware.photon.controller.api.model.VmState;
import com.vmware.photon.controller.cloudstore.xenon.entity.HostService;
import com.vmware.photon.controller.cloudstore.xenon.entity.HostServiceFactory;
import com.vmware.photon.controller.common.clients.HostClient;
import com.vmware.photon.controller.common.clients.exceptions.SystemErrorException;
import com.vmware.photon.controller.common.clients.exceptions.VmNotFoundException;
import com.vmware.photon.controller.common.xenon.exceptions.DocumentNotFoundException;
import com.vmware.photon.controller.host.gen.GetVmNetworkResponse;
import com.vmware.photon.controller.host.gen.GetVmNetworkResultCode;
import com.vmware.photon.controller.host.gen.Ipv4Address;
import com.vmware.photon.controller.host.gen.VmNetworkInfo;
import com.vmware.photon.controller.resource.gen.Datastore;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import org.mockito.InOrder;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.powermock.modules.testng.PowerMockTestCase;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.spy;
import static org.testng.AssertJUnit.fail;

import java.util.ArrayList;
import java.util.List;


/**
 * Tests {@link VmGetNetworksStepCmd}.
 */
public class VmGetNetworksStepCmdTest extends PowerMockTestCase {
  @Mock
  private StepBackend stepBackend;

  @Mock
  private TaskBackend taskBackend;

  @Mock
  private NetworkBackend networkBackend;

  @Mock
  private VmBackend vmBackend;

  @Mock
  private NetworkHelper networkHelper;

  @Mock
  private HostClient hostClient;

  @Mock
  private ApiFeXenonRestClient xenonClient;

  @Mock
  private PhotonControllerXenonRestClient photonControllerXenonRestClient;

  @Mock
  private TaskEntity task;

  @Mock
  private HousekeeperClient housekeeperClient;

  @Mock
  private DeployerClient deployerClient;

  @Mock
  private com.vmware.photon.controller.api.frontend.backends.clients.DeployerClient deployerXenonClient;

  @Mock
  private com.vmware.photon.controller.api.frontend.backends.clients.HousekeeperClient housekeeperXenonClient;

  @Mock
  private EntityLockBackend entityLockBackend;

  @Mock
  private com.vmware.xenon.common.Operation hostServiceOp;

  private TaskCommand taskCommand;

  private VmEntity vm;

  private List<VmNetworkInfo> vmNetworks;

  private GetVmNetworkResponse vmNetworkResponse;

  private StepEntity step;
  private String stepId = "step-1";
  private String vmId = "vm-1";

  @BeforeMethod
  public void setUp() throws Exception, DocumentNotFoundException {
    task = new TaskEntity();
    task.setId("task-1");

    vm = new VmEntity();
    vm.setId(vmId);
    vm.setState(VmState.STARTED);
    vm.setAgent("agent-id");

    VmNetworkInfo networkInfo = new VmNetworkInfo();
    networkInfo.setMac_address("00:50:56:02:00:3f");
    networkInfo.setIp_address(new Ipv4Address("10.146.30.120", "255.255.255.0"));
    vmNetworks = new ArrayList<>();
    vmNetworks.add(networkInfo);

    vmNetworkResponse = new GetVmNetworkResponse(GetVmNetworkResultCode.OK);
    vmNetworkResponse.setNetwork_info(vmNetworks);

    Datastore datastore = new Datastore();
    datastore.setId("datastore-id");

    taskCommand = spy(new TaskCommand(xenonClient, photonControllerXenonRestClient, hostClient,
        housekeeperClient, deployerClient, deployerXenonClient, housekeeperXenonClient,
        entityLockBackend, task));
    when(taskCommand.getHostClient()).thenReturn(hostClient);
    when(taskCommand.getPhotonControllerXenonRestClient()).thenReturn(photonControllerXenonRestClient);
    HostService.State hostServiceState = new HostService.State();
    hostServiceState.hostAddress = "host-ip";
    when(hostServiceOp.getBody(Matchers.any())).thenReturn(hostServiceState);
    when(xenonClient.get(Matchers.startsWith(HostServiceFactory.SELF_LINK))).thenReturn(hostServiceOp);

    when(taskCommand.getTask()).thenReturn(task);
  }

  @Test
  public void testSuccessfulGetNetwork() throws Exception {
    when(hostClient.getVmNetworks(vmId))
        .thenReturn(vmNetworkResponse);

    VmGetNetworksStepCmd command = getCommand();
    command.execute();

    InOrder inOrder = inOrder(hostClient, taskBackend);
    inOrder.verify(hostClient).getVmNetworks(vmId);
    inOrder.verify(taskBackend).setTaskResourceProperties(any(TaskEntity.class), any(String.class));

    verifyNoMoreInteractions(taskBackend);
  }

  @Test
  public void testGetNetworkWithPortGroup() throws Exception {
    when(hostClient.getVmNetworks(vmId))
        .thenReturn(vmNetworkResponse);

    vmNetworks.get(0).setNetwork("PG1");
    Subnet subnet = new Subnet();
    subnet.setId("network-id");

    when(networkBackend.filter(Optional.<String>absent(), Optional.of("PG1"),
        Optional.of(PaginationConfig.DEFAULT_DEFAULT_PAGE_SIZE)))
        .thenReturn(new ResourceList<>(ImmutableList.of(subnet)));
    VmGetNetworksStepCmd command = getCommand();
    command.execute();

    InOrder inOrder = inOrder(hostClient, taskBackend);
    inOrder.verify(hostClient).getVmNetworks(vmId);
    inOrder.verify(taskBackend).setTaskResourceProperties(task,
        "{\"networkConnections\":[{\"network\":\"network-id\",\"macAddress\":\"00:50:56:02:00:3f\"," +
            "\"ipAddress\":\"10.146.30.120\",\"netmask\":\"255.255.255.0\",\"isConnected\":\"Unknown\"}]}");

    verifyNoMoreInteractions(taskBackend);
  }

  @Test
  public void testGetNetworkWithPortGroupMatchingNoNetwork() throws Exception {
    when(hostClient.getVmNetworks(vmId))
        .thenReturn(vmNetworkResponse);

    vmNetworks.get(0).setNetwork("PG1");

    when(networkBackend.filter(Optional.<String>absent(), Optional.of("PG1"),
        Optional.of(PaginationConfig.DEFAULT_DEFAULT_PAGE_SIZE))).thenReturn(new ResourceList<>(new ArrayList<>()));
    VmGetNetworksStepCmd command = getCommand();
    command.execute();

    InOrder inOrder = inOrder(hostClient, taskBackend);
    inOrder.verify(hostClient).getVmNetworks(vmId);
    inOrder.verify(taskBackend).setTaskResourceProperties(task,
        "{\"networkConnections\":[{\"network\":\"PG1\",\"macAddress\":\"00:50:56:02:00:3f\"," +
            "\"ipAddress\":\"10.146.30.120\",\"netmask\":\"255.255.255.0\",\"isConnected\":\"Unknown\"}]}");

    verifyNoMoreInteractions(taskBackend);
  }

  @Test
  public void testGetNetworkWithRetryTimeout() throws Exception {
    when(hostClient.getVmNetworks(vmId))
            .thenReturn(vmNetworkResponse);

    vmNetworks.get(0).setNetwork("PG1");
    Subnet subnet = new Subnet();
    subnet.setId("network-id");

    when(networkBackend.filter(Optional.<String>absent(), Optional.of("PG1"),
            Optional.of(PaginationConfig.DEFAULT_DEFAULT_PAGE_SIZE)))
            .thenReturn(new ResourceList<>(ImmutableList.of(subnet)));
    VmGetNetworksStepCmd command = getCommandWithRetryTimeout();
    command.execute();

    InOrder inOrder = inOrder(hostClient, taskBackend);
    inOrder.verify(hostClient).getVmNetworks(vmId);
    inOrder.verify(taskBackend).setTaskResourceProperties(task,
            "{\"networkConnections\":[{\"network\":\"network-id\",\"macAddress\":\"00:50:56:02:00:3f\"," +
                    "\"ipAddress\":\"10.146.30.120\",\"netmask\":\"255.255.255.0\",\"isConnected\":\"Unknown\"}]}");

    verifyNoMoreInteractions(taskBackend);
  }

  @Test
  public void testVmNotFoundExceptionInNonErrorState() throws Exception {
    when(hostClient.getVmNetworks(vmId)).thenThrow(new VmNotFoundException("Error"));

    VmGetNetworksStepCmd command = getCommand();
    try {
      command.execute();
      fail("should have failed due to Internal exception");
    } catch (InternalException ex) {
    }
  }

  @Test
  public void testVmNotFoundExceptionInErrorState() throws Throwable {
    vm.setState(VmState.ERROR);
    when(hostClient.getVmNetworks(vmId)).thenThrow(new VmNotFoundException("Error"));

    VmGetNetworksStepCmd command = getCommand();
    try {
      command.execute();
      fail("should have failed due to Internal exception");
    } catch (UnsupportedOperationException ex) {
      assertThat(ex.getMessage(), containsString(
          String.format("Unsupported operation GET_NETWORKS for vm/%s in state %s", vm.getId(), vm.getState())));
    }

  }

  @Test
  public void testFailedGetNetwork() throws Throwable {
    when(hostClient.getVmNetworks(vmId)).thenThrow(new SystemErrorException("e"));

    VmGetNetworksStepCmd command = getCommand();
    try {
      command.execute();
      fail("should have failed due to SystemErrorException exception");
    } catch (SystemErrorException e) {
    }
  }

  @Test
  public void testOptionalFields() throws Throwable {
    GetVmNetworkResponse response = new GetVmNetworkResponse();
    response.addToNetwork_info(new VmNetworkInfo());
    when(hostClient.getVmNetworks(vmId)).thenReturn(response);

    VmGetNetworksStepCmd command = getCommand();
    // VmNetworkInfo with null fields should not cause NPE
    command.execute();
  }

  private void setVmInStepEntity() {
    step = new StepEntity();
    step.setId(stepId);
    step.addResource(vm);
  }

  private VmGetNetworksStepCmd getCommand() {
    setVmInStepEntity();

    return spy(new VmGetNetworksStepCmd(taskCommand, stepBackend, step, taskBackend, networkBackend, vmBackend,
            networkHelper));
  }

  private VmGetNetworksStepCmd getCommandWithRetryTimeout() {
    setVmInStepEntity();
    step.createOrUpdateTransientResource(VmGetNetworksStepCmd.RETRY_TIMEOUT_KEY,
            VmGetNetworksStepCmd.DEFAULT_POLL_INTERVAL);

    return spy(new VmGetNetworksStepCmd(taskCommand, stepBackend, step, taskBackend, networkBackend, vmBackend,
            networkHelper));
  }
}
