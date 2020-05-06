package org.zstack.network.service.virtualrouter.dhcp;

import org.springframework.beans.factory.annotation.Autowired;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.cloudbus.CloudBusCallBack;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.core.timeout.ApiTimeoutManager;
import org.zstack.header.core.Completion;
import org.zstack.header.core.NoErrorCompletion;
import org.zstack.header.core.ReturnValueCompletion;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.message.MessageReply;
import org.zstack.header.network.service.*;
import org.zstack.header.vm.VmInstanceConstant;
import org.zstack.header.vm.VmInstanceInventory;
import org.zstack.header.vm.VmInstanceSpec;
import org.zstack.header.vm.VmNicInventory;
import org.zstack.network.service.virtualrouter.*;
import org.zstack.network.service.virtualrouter.VirtualRouterCommands.AddDhcpEntryRsp;
import org.zstack.network.service.virtualrouter.ha.VirtualRouterHaBackend;
import org.zstack.utils.CollectionDSL;
import org.zstack.utils.CollectionUtils;
import org.zstack.utils.Utils;
import org.zstack.utils.function.Function;
import org.zstack.utils.gson.JSONObjectUtil;
import org.zstack.utils.logging.CLogger;

import static org.zstack.core.Platform.operr;

import java.util.*;

public class VirtualRouterDhcpBackend extends AbstractVirtualRouterBackend implements NetworkServiceDhcpBackend {
    private final CLogger logger = Utils.getLogger(VirtualRouterDhcpBackend.class);

    @Autowired
    protected ErrorFacade errf;
    @Autowired
    protected CloudBus bus;
    @Autowired
    protected ApiTimeoutManager apiTimeoutManager;
    @Autowired
    private VirtualRouterHaBackend haBackend;
    @Autowired
    private DatabaseFacade dbf;

    @Override
    public NetworkServiceProviderType getProviderType() {
        return VirtualRouterConstant.PROVIDER_TYPE;
    }

    private void doApplyDhcpEntryToVirtualRouter(VirtualRouterVmInventory vr, VirtualRouterCommands.DhcpInfo info, Completion completion) {
        VirtualRouterCommands.AddDhcpEntryCmd cmd = new VirtualRouterCommands.AddDhcpEntryCmd();
        cmd.setDhcpEntries(Arrays.asList(info));
        VirtualRouterAsyncHttpCallMsg cmsg = new VirtualRouterAsyncHttpCallMsg();
        cmsg.setCommand(cmd);
        cmsg.setPath(VirtualRouterConstant.VR_ADD_DHCP_PATH);
        cmsg.setVmInstanceUuid(vr.getUuid());
        cmsg.setCheckStatus(true);
        bus.makeTargetServiceIdByResourceUuid(cmsg, VmInstanceConstant.SERVICE_ID, vr.getUuid());
        bus.send(cmsg, new CloudBusCallBack(completion) {
            @Override
            public void run(MessageReply reply) {
                if (!reply.isSuccess()) {
                    completion.fail(reply.getError());
                    return;
                }

                VirtualRouterAsyncHttpCallReply re = reply.castReply();
                AddDhcpEntryRsp rsp =  re.toResponse(AddDhcpEntryRsp.class);
                if (rsp.isSuccess()) {
                    new VirtualRouterRoleManager().makeDhcpRole(vr.getUuid());
                    logger.debug(String.format("successfully add dhcp entry[%s] to virtual router vm[uuid:%s, ip:%s]",
                            JSONObjectUtil.toJsonString(info), vr.getUuid(), vr.getManagementNic().getIp()));
                    completion.success();
                } else {
                    ErrorCode err = operr("unable to add dhcp entries to virtual router vm[uuid:%s ip:%s], because %s, dhcp entry[%s]",
                            vr.getUuid(), vr.getManagementNic().getIp(), rsp.getError(), JSONObjectUtil.toJsonString(info));
                    completion.fail(err);
                }
            }
        });
    }

    private void applyDhcpEntryToHAVirtualRouter(VirtualRouterVmInventory vr, VirtualRouterCommands.DhcpInfo info, Completion completion) {
        Map<String, Object> data = new HashMap<>();
        data.put(VirtualRouterHaCallbackInterface.Params.TaskName.toString(), "applyDHCP");
        data.put(VirtualRouterHaCallbackInterface.Params.OriginRouterUuid.toString(), vr.getUuid());
        data.put(VirtualRouterHaCallbackInterface.Params.Struct.toString(), info);
        haBackend.submitVirutalRouterHaTask(new VirtualRouterHaCallbackInterface() {
            @Override
            public void callBack(String vrUuid, Map<String, Object> data, Completion compl) {
                VirtualRouterVmVO vrVO = dbf.findByUuid(vrUuid, VirtualRouterVmVO.class);
                if (vrVO == null) {
                    logger.debug(String.format("VirtualRouter[uuid:%s] is deleted, no need apply Eip on backend", vrUuid));
                    compl.success();
                    return;
                }

                VirtualRouterVmInventory vr = VirtualRouterVmInventory.valueOf(vrVO);
                VirtualRouterCommands.DhcpInfo info = (VirtualRouterCommands.DhcpInfo)data.get(VirtualRouterHaCallbackInterface.Params.Struct.toString());
                doApplyDhcpEntryToVirtualRouter(vr, info, compl);
            }
        }, data, completion);
    }

    private void applyDhcpEntryToVirtualRouter(VirtualRouterVmInventory vr, DhcpStruct struct, Completion completion) {
        VirtualRouterCommands.DhcpInfo info = new VirtualRouterCommands.DhcpInfo();
        info.setGateway(struct.getGateway());
        info.setIp(struct.getIp());
        info.setDefaultL3Network(struct.isDefaultL3Network());
        info.setMac(struct.getMac());
        info.setGateway(struct.getGateway());
        info.setNetmask(struct.getNetmask());
        info.setDnsDomain(struct.getDnsDomain());
        info.setHostname(struct.getHostname());
        info.setMtu(struct.getMtu());

        if (info.isDefaultL3Network()) {
            if (info.getHostname() == null) {
                info.setHostname(info.getIp().replaceAll("\\.", "-"));
            }

            if (info.getDnsDomain() != null) {
                info.setHostname(String.format("%s.%s", info.getHostname(), info.getDnsDomain()));
            }
        }

        VmNicInventory vrNic = CollectionUtils.find(vr.getVmNics(), new Function<VmNicInventory, VmNicInventory>() {
            @Override
            public VmNicInventory call(VmNicInventory arg) {
                return arg.getL3NetworkUuid().equals(struct.getL3Network().getUuid()) ? arg : null;
            }
        });
        info.setVrNicMac(vrNic.getMac());
        if (struct.isDefaultL3Network()) {
            /*if there is no DNS service, the DHCP uses the external DNS service. ZSTAC-13262 by miaozhanyong*/
            if (struct.getL3Network().getNetworkServiceTypes().contains(NetworkServiceType.DNS.toString())) {
                info.setDns(CollectionDSL.list(vrNic.getIp()));
            } else {
                info.setDns(struct.getL3Network().getDns());
            }
        }

        doApplyDhcpEntryToVirtualRouter(vr, info, new Completion(completion) {
            @Override
            public void success() {
                applyDhcpEntryToHAVirtualRouter(vr, info, completion);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    private void applyDhcpEntry(final Iterator<DhcpStruct> it, final VmInstanceSpec spec, final Completion completion) {
        if (!it.hasNext()) {
            completion.success();
            return;
        }

        final DhcpStruct struct = it.next();

        VirtualRouterStruct s = new VirtualRouterStruct();
        s.setL3Network(struct.getL3Network());

        acquireVirtualRouterVm(s, new ReturnValueCompletion<VirtualRouterVmInventory>(completion) {
            @Override
            public void success(final VirtualRouterVmInventory vr) {
                applyDhcpEntryToVirtualRouter(vr, struct, new Completion(completion) {
                    @Override
                    public void success() {
                        applyDhcpEntry(it, spec, completion);
                    }

                    @Override
                    public void fail(ErrorCode errorCode) {
                        completion.fail(errorCode);
                    }
                });
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }

    @Override
    public void applyDhcpService(final List<DhcpStruct> dhcpStructList, final VmInstanceSpec spec, final Completion completion) {
        if (dhcpStructList.isEmpty()) {
            completion.success();
            return;
        }

        applyDhcpEntry(dhcpStructList.iterator(), spec, completion);
    }

    private void releaseDhcpFromHaVirtualRouter(VirtualRouterVmInventory vr, VirtualRouterCommands.DhcpInfo info, final NoErrorCompletion completion) {
        Map<String, Object> data = new HashMap<>();
        data.put(VirtualRouterHaCallbackInterface.Params.TaskName.toString(), "applyDHCP");
        data.put(VirtualRouterHaCallbackInterface.Params.OriginRouterUuid.toString(), vr.getUuid());
        data.put(VirtualRouterHaCallbackInterface.Params.Struct.toString(), info);
        haBackend.submitVirutalRouterHaTask(new VirtualRouterHaCallbackInterface() {
            @Override
            public void callBack(String vrUuid, Map<String, Object> data, Completion compl) {
                VirtualRouterVmVO vrVO = dbf.findByUuid(vrUuid, VirtualRouterVmVO.class);
                if (vrVO == null) {
                    logger.debug(String.format("VirtualRouter[uuid:%s] is deleted, no need release Eip on backend", vrUuid));
                    compl.success();
                    return;
                }

                VirtualRouterVmInventory vr = VirtualRouterVmInventory.valueOf(vrVO);
                VirtualRouterCommands.DhcpInfo info = (VirtualRouterCommands.DhcpInfo) data.get(VirtualRouterHaCallbackInterface.Params.Struct.toString());
                doReleaseDhcpFromVirtualRouter(vr, info, new NoErrorCompletion(compl) {
                    @Override
                    public void done() {
                        compl.success();
                    }
                });
            }
        }, data, new Completion(completion) {
            @Override
            public void success() {
                completion.done();
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.done();
            }
        });
    }

    private void doReleaseDhcpFromVirtualRouter(VirtualRouterVmInventory vr, VirtualRouterCommands.DhcpInfo info, final NoErrorCompletion completion) {
        VirtualRouterCommands.RemoveDhcpEntryCmd cmd = new VirtualRouterCommands.RemoveDhcpEntryCmd();
        cmd.setDhcpEntries(Arrays.asList(info));

        VirtualRouterAsyncHttpCallMsg msg = new VirtualRouterAsyncHttpCallMsg();
        msg.setCheckStatus(true);
        msg.setVmInstanceUuid(vr.getUuid());
        msg.setPath(VirtualRouterConstant.VR_REMOVE_DHCP_PATH);
        msg.setCommand(cmd);
        bus.makeTargetServiceIdByResourceUuid(msg, VmInstanceConstant.SERVICE_ID, vr.getUuid());
        bus.send(msg, new CloudBusCallBack(completion) {
            @Override
            public void run(MessageReply reply) {
                if (!reply.isSuccess()) {
                    logger.warn(String.format("unable to remove dhcp entry[%s] from virtual router vm[uuid:%s, ip:%s], %s",
                            JSONObjectUtil.toJsonString(info), vr.getUuid(), vr.getManagementNic().getIp(), reply.getError()));
                    //TODO: GC
                } else {
                    VirtualRouterAsyncHttpCallReply ret = reply.castReply();
                    if (ret.isSuccess()) {
                        logger.debug(String.format("successfully removed dhcp entry[%s] from virtual router vm[uuid:%s, ip:%s]",
                                JSONObjectUtil.toJsonString(info), vr.getUuid(), vr
                                .getManagementNic().getIp()));
                    } else {
                        logger.warn(String.format("unable to remove dhcp entry[%s] from virtual router vm[uuid:%s, ip:%s], %s",
                                JSONObjectUtil.toJsonString(info), vr.getUuid(), vr
                                .getManagementNic().getIp(), ret.getError()));
                        //TODO: GC
                    }
                }

                completion.done();
            }
        });
    }

    private void releaseDhcpFromVirtualRouter(VirtualRouterVmInventory vr, DhcpStruct struct, final NoErrorCompletion completion) {
        VmNicInventory vrNic = CollectionUtils.find(vr.getVmNics(), new Function<VmNicInventory, VmNicInventory>() {
            @Override
            public VmNicInventory call(VmNicInventory arg) {
                return arg.getL3NetworkUuid().equals(struct.getL3Network().getUuid()) ? arg : null;
            }
        });

        VirtualRouterCommands.DhcpInfo info = new VirtualRouterCommands.DhcpInfo();
        info.setGateway(struct.getGateway());
        info.setDefaultL3Network(struct.isDefaultL3Network());
        info.setIp(struct.getIp());
        info.setMac(struct.getMac());
        info.setNetmask(struct.getNetmask());
        info.setVrNicMac(vrNic.getMac());

        doReleaseDhcpFromVirtualRouter(vr, info, new NoErrorCompletion(completion) {
            @Override
            public void done() {
                releaseDhcpFromHaVirtualRouter(vr, info, completion);
            }
        });
    }

    private void releaseDhcp(final Iterator<DhcpStruct> it, final VmInstanceSpec spec, final NoErrorCompletion completion) {
        if (!it.hasNext()) {
            completion.done();
            return;
        }

        final DhcpStruct struct = it.next();
        if (!vrMgr.isVirtualRouterRunningForL3Network(struct.getL3Network().getUuid())) {
            logger.debug(String.format("virtual router for l3Network[uuid:%s] is not running, skip releasing DHCP", struct.getL3Network().getUuid()));
            releaseDhcp(it, spec, completion);
            return;
        }

        final VirtualRouterVmInventory vr = vrMgr.getVirtualRouterVm(struct.getL3Network());
        releaseDhcpFromVirtualRouter(vr, struct, new NoErrorCompletion(completion) {
            @Override
            public void done() {
                releaseDhcp(it, spec, completion);
            }
        });
    }

    @Override
    public void releaseDhcpService(List<DhcpStruct> dhcpStructList, VmInstanceSpec spec, NoErrorCompletion completion) {
        if (dhcpStructList.isEmpty()) {
            completion.done();
            return;
        }

        releaseDhcp(dhcpStructList.iterator(), spec, completion);
    }

    @Override
    public void vmDefaultL3NetworkChanged(VmInstanceInventory vm, String previousL3, String nowL3, Completion completion) {
        completion.success();
    }
}
