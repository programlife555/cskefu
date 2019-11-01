/*
 * Copyright (C) 2019 Chatopera Inc, <https://www.chatopera.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.chatopera.cc.handler.apps.cca;

import com.chatopera.cc.acd.AutomaticServiceDist;
import com.chatopera.cc.basic.MainUtils;
import com.chatopera.cc.cache.Cache;
import com.chatopera.cc.exception.CSKefuException;
import com.chatopera.cc.model.*;
import com.chatopera.cc.peer.PeerSyncIM;
import com.chatopera.cc.proxy.AgentServiceProxy;
import com.chatopera.cc.basic.MainContext;
import com.chatopera.cc.handler.Handler;
import com.chatopera.cc.model.AgentUser;
import com.chatopera.cc.model.Organ;
import com.chatopera.cc.model.User;
import com.chatopera.cc.persistence.es.QuickReplyRepository;
import com.chatopera.cc.persistence.repository.*;
import com.chatopera.cc.proxy.AgentUserProxy;
import com.chatopera.cc.proxy.OnlineUserProxy;
import com.chatopera.cc.proxy.UserProxy;
import com.chatopera.cc.socketio.message.Message;
import com.chatopera.cc.util.Menu;
import freemarker.template.TemplateException;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ui.ModelMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.io.IOException;
import java.util.*;

@Controller
@RequestMapping(value = "/apps/cca")
public class AgentAuditController extends Handler {
    private final static Logger logger = LoggerFactory.getLogger(AgentAuditController.class);

    @Autowired
    private AgentUserRepository agentUserRes;

    @Autowired
    private OrganRepository organRes;

    @Autowired
    private UserRepository userRes;

    @Autowired
    private AgentUserRepository agentUserRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private AgentServiceRepository agentServiceRes;

    @Autowired
    private AgentUserProxy agentUserProxy;

    @Autowired
    private AgentUserTaskRepository agentUserTaskRes;

    @Autowired
    private ServiceSummaryRepository serviceSummaryRes;

    @Autowired
    private StatusEventRepository statusEventRes;

    @Autowired
    private OnlineUserRepository onlineUserRes;

    @Autowired
    private PbxHostRepository pbxHostRes;

    @Autowired
    private TagRepository tagRes;

    @Autowired
    private Cache cache;

    @Autowired
    private PeerSyncIM peerSyncIM;

    @Autowired
    private QuickReplyRepository quickReplyRes;

    @Autowired
    private QuickTypeRepository quickTypeRes;

    @Autowired
    private TagRelationRepository tagRelationRes;

    @Autowired
    private AgentServiceProxy agentServiceProxy;

    @RequestMapping(value = "/index")
    @Menu(type = "cca", subtype = "cca", access = true)
    public ModelAndView index(
            ModelMap map,
            HttpServletRequest request,
            @Valid final String skill,
            @Valid final String agentno,
            @Valid String sort) {
        final String orgi = super.getOrgi(request);
        final User logined = super.getUser(request);
        logger.info("[index] skill {}, agentno {}, logined {}", skill, agentno, logined.getId());

        ModelAndView view = request(super.createAppsTempletResponse("/apps/cca/index"));
        Sort defaultSort = null;

        if (StringUtils.isNotBlank(sort)) {
            List<Sort.Order> criterias = new ArrayList<>();
            if (sort.equals("lastmessage")) {
                criterias.add(new Sort.Order(Sort.Direction.DESC, "status"));
                criterias.add(new Sort.Order(Sort.Direction.DESC, "lastmessage"));
            } else if (sort.equals("logintime")) {
                criterias.add(new Sort.Order(Sort.Direction.DESC, "status"));
                criterias.add(new Sort.Order(Sort.Direction.DESC, "createtime"));
            } else if (sort.equals("default")) {
                defaultSort = new Sort(Sort.Direction.DESC, "status");
            }
            if (criterias.size() > 0) {
                defaultSort = new Sort(criterias);
                map.addAttribute("sort", sort);
            }
        } else {
            defaultSort = new Sort(Sort.Direction.DESC, "status");
        }

        // 坐席对话列表
        List<AgentUser> agentUsers;

        if (StringUtils.isBlank(skill) && StringUtils.isBlank(agentno)) {
            agentUsers = agentUserRes.findByOrgiAndStatusAndAgentnoIsNot(
                    orgi, MainContext.AgentUserStatusEnum.INSERVICE.toString(), logined.getId(), defaultSort);
        } else if (StringUtils.isNotBlank(skill) && StringUtils.isNotBlank(agentno)) {
            view.addObject("skill", skill);
            view.addObject("agentno", agentno);
            agentUsers = agentUserRes.findByOrgiAndStatusAndSkillAndAgentno(
                    orgi, MainContext.AgentUserStatusEnum.INSERVICE.toString(), skill, agentno, defaultSort);
        } else if (StringUtils.isNotBlank(skill)) {
            view.addObject("skill", skill);
            agentUsers = agentUserRes.findByOrgiAndStatusAndSkillAndAgentnoIsNot(
                    orgi, MainContext.AgentUserStatusEnum.INSERVICE.toString(), skill, agentno, defaultSort);
        } else {
            // agent is not Blank
            view.addObject("agentno", agentno);
            agentUsers = agentUserRes.findByOrgiAndStatusAndAgentno(
                    orgi, MainContext.AgentUserStatusEnum.INSERVICE.toString(), agentno, defaultSort);
        }

        logger.info("[index] agent users size: {}", agentUsers.size());

        if (agentUsers.size() > 0) {
            view.addObject("agentUserList", agentUsers);

            /**
             * 当前对话
             */
            final AgentUser currentAgentUser = agentUsers.get(0);
            agentServiceProxy.bundleDialogRequiredDataInView(view, map, currentAgentUser, orgi, logined);
        }

        // 查询所有技能组
        List<Organ> skills = organRes.findByOrgiAndSkill(orgi, true);
        List<User> agents = userRes.findByOrgiAndAgentAndDatastatusAndIdIsNot(orgi, true, false, logined.getId());

        view.addObject("skillGroups", skills);
        view.addObject("agentList", agents);

        return view;
    }

    /**
     * 坐席转接窗口
     *
     * @param map
     * @param request
     * @param userid
     * @param agentserviceid
     * @param agentuserid
     * @return
     */
    @RequestMapping(value = "/transfer")
    @Menu(type = "apps", subtype = "transfer")
    public ModelAndView transfer(
            ModelMap map,
            final HttpServletRequest request,
            final @Valid String userid,
            final @Valid String agentserviceid,
            final @Valid String agentnoid,
            final @Valid String agentuserid) {
        logger.info("[transfer] userId {}, agentUser {}", userid, agentuserid);
        final String orgi = super.getOrgi(request);
        final User logined = super.getUser(request);
        if (StringUtils.isNotBlank(userid) && StringUtils.isNotBlank(agentuserid)) {
            // 列出所有技能组
            final List<Organ> skillGroups = OnlineUserProxy.organ(orgi, true);

            // 选择当前用户的默认技能组
            Set<String> organs = super.getUser(request).getOrgans().keySet();
            String currentOrgan = organs.size() > 0 ? (new ArrayList<String>(organs)).get(0) : null;

            if (StringUtils.isBlank(currentOrgan)) {
                if (!skillGroups.isEmpty()) {
                    currentOrgan = skillGroups.get(0).getId();
                }
            }

            // 列出所有在线的坐席，排除本身
            final Map<String, AgentStatus> agentStatusMap = cache.findAllReadyAgentStatusByOrgi(orgi);

            List<String> userids = new ArrayList<>();

            for (final String o : agentStatusMap.keySet()) {
                if (!StringUtils.equals(o, agentnoid)) {
                    userids.add(o);
                }
            }

            final List<User> userList = userRes.findAll(userids);
            for (final User o : userList) {
                o.setAgentStatus(agentStatusMap.get(o.getId()));
                // find user's skills
                UserProxy.attachOrgansPropertiesForUser(o);
            }

            map.addAttribute("userList", userList);
            map.addAttribute("userid", userid);
            map.addAttribute("agentserviceid", agentserviceid);
            map.addAttribute("agentuserid", agentuserid);
            map.addAttribute("agentnoid", agentnoid);
            map.addAttribute("skillGroups", skillGroups);
            map.addAttribute("agentservice", this.agentServiceRes.findByIdAndOrgi(agentserviceid, orgi));
            map.addAttribute("currentorgan", currentOrgan);
        }

        return request(super.createRequestPageTempletResponse("/apps/cca/transfer"));
    }


    /**
     * 查找一个组织机构中的在线坐席
     *
     * @param map
     * @param request
     * @param organ
     * @return
     */
    @RequestMapping(value = "/transfer/agent")
    @Menu(type = "apps", subtype = "transferagent")
    public ModelAndView transferagent(
            ModelMap map,
            HttpServletRequest request,
            @Valid String agentnoid,
            @Valid String organ) {
        final String orgi = super.getOrgi(request);
        if (StringUtils.isNotBlank(organ)) {
            List<String> usersids = new ArrayList<String>();
            final List<AgentStatus> agentStatusList = cache.getAgentStatusBySkillAndOrgi(organ, orgi);
            if (agentStatusList.size() > 0) {
                for (AgentStatus agentStatus : agentStatusList) {
                    if (agentStatus != null && !StringUtils.equals(agentStatus.getAgentno(), agentnoid)) {
                        usersids.add(agentStatus.getAgentno());
                    }
                }
            }
            List<User> userList = userRes.findAll(usersids);
            for (User user : userList) {
                UserProxy.attachOrgansPropertiesForUser(user);
                for (final AgentStatus as : agentStatusList) {
                    if (StringUtils.equals(as.getAgentno(), user.getId())) {
                        user.setAgentStatus(as);
                        break;
                    }
                }
            }

            map.addAttribute("userList", userList);
            map.addAttribute("currentorgan", organ);
        }
        return request(super.createRequestPageTempletResponse("/apps/cca/transferagentlist"));
    }

    /**
     * 执行坐席转接
     *
     * @param map
     * @param request
     * @param userid
     * @param agentserviceid
     * @param agentuserid
     * @param agentno
     * @param memo
     * @return
     */
    @RequestMapping(value = "/transfer/save")
    @Menu(type = "apps", subtype = "transfersave")
    public ModelAndView transfersave(
            final ModelMap map, HttpServletRequest request,
            @Valid final String userid,         // 访客ID
            @Valid final String agentserviceid, // 服务记录ID
            @Valid final String agentuserid,    // 坐席访客ID
            @Valid final String currentAgentnoid,
            @Valid final String agentno,   // 会话转接给下一个坐席
            @Valid final String memo) throws CSKefuException {
        final String currentAgentno = currentAgentnoid; // 当前会话坐席的agentno

        final String orgi = super.getOrgi(request);

        if (StringUtils.isNotBlank(userid) &&
                StringUtils.isNotBlank(agentuserid) &&
                StringUtils.isNotBlank(agentno)) {
            final User targetAgent = userRes.findOne(agentno);
            final AgentService agentService = agentServiceRes.findByIdAndOrgi(agentserviceid, super.getOrgi(request));
            /**
             * 更新AgentUser
             */
            final AgentUser agentUser = agentUserProxy.resolveAgentUser(userid, agentuserid, orgi);
            agentUser.setAgentno(agentno);
            agentUser.setAgentname(targetAgent.getUname());
            agentUserRes.save(agentUser);

            /**
             * 坐席状态
             */
            // 转接目标坐席
            final AgentStatus transAgentStatus = cache.findOneAgentStatusByAgentnoAndOrig(agentno, orgi);

            // 转接源坐席
            final AgentStatus currentAgentStatus = cache.findOneAgentStatusByAgentnoAndOrig(currentAgentno, orgi);

            if (StringUtils.equals(
                    MainContext.AgentUserStatusEnum.INSERVICE.toString(), agentUser.getStatus())) { //转接 ， 发送消息给 目标坐席

                // 更新当前坐席的服务访客列表
                if (currentAgentStatus != null) {
                    cache.deleteOnlineUserIdFromAgentStatusByUseridAndAgentnoAndOrgi(userid, currentAgentno, orgi);
                    AutomaticServiceDist.updateAgentStatus(currentAgentStatus, super.getOrgi(request));
                }

                if (transAgentStatus != null) {
                    agentService.setAgentno(agentno);
                    agentService.setAgentusername(transAgentStatus.getUsername());
                }

                // 转接坐席提示消息
                try {
                    Message outMessage = new Message();
                    outMessage.setMessage(
                            AutomaticServiceDist.getSuccessMessage(agentService, agentUser.getChannel(), orgi));
                    outMessage.setMessageType(MainContext.MediaType.TEXT.toString());
                    outMessage.setCalltype(MainContext.CallType.IN.toString());
                    outMessage.setCreatetime(MainUtils.dateFormate.format(new Date()));
                    outMessage.setAgentUser(agentUser);
                    outMessage.setAgentService(agentService);

                    if (StringUtils.isNotBlank(agentUser.getUserid())) {
                        peerSyncIM.send(
                                MainContext.ReceiverType.VISITOR,
                                MainContext.ChannelType.toValue(agentUser.getChannel()),
                                agentUser.getAppid(),
                                MainContext.MessageType.STATUS,
                                agentUser.getUserid(),
                                outMessage,
                                true);
                    }

                    // 通知转接消息给新坐席
                    outMessage.setChannelMessage(agentUser);
                    outMessage.setAgentUser(agentUser);
                    peerSyncIM.send(
                            MainContext.ReceiverType.AGENT, MainContext.ChannelType.WEBIM,
                            agentUser.getAppid(), MainContext.MessageType.NEW, agentService.getAgentno(),
                            outMessage, true);

                } catch (Exception ex) {
                    logger.error("[transfersave]", ex);
                }
            }

            if (agentService != null) {
                agentService.setAgentno(agentno);
                if (StringUtils.isNotBlank(memo)) {
                    agentService.setTransmemo(memo);
                }
                agentService.setTrans(true);
                agentService.setTranstime(new Date());
                agentServiceRes.save(agentService);
            }
        }
        return request(super.createRequestPageTempletResponse("redirect:/cca/index.html"));
    }


    @RequestMapping("/agentusers")
    @Menu(type = "apps", subtype = "cca")
    public ModelAndView agentusers(HttpServletRequest request, String userid) {
        ModelAndView view = request(super.createRequestPageTempletResponse("/apps/cca/agentusers"));
        User logined = super.getUser(request);
        view.addObject(
                "agentUserList", agentUserRepository.findByAgentnoAndOrgi(logined.getId(), logined.getOrgi(),
                                                                          new Sort(Sort.Direction.DESC, "status")));
        List<AgentUser> agentUserList = agentUserRepository.findByUseridAndOrgi(userid, logined.getOrgi());
        view.addObject(
                "curagentuser", agentUserList != null && agentUserList.size() > 0 ? agentUserList.get(0) : null);

        return view;
    }

    @RequestMapping("/agentuser")
    @Menu(type = "apps", subtype = "cca")
    public ModelAndView agentuser(
            ModelMap map,
            HttpServletRequest request,
            String id,
            String channel) throws IOException, TemplateException {
        String mainagentuser = "/apps/cca/mainagentuser";
        if (channel.equals("phone")) {
            mainagentuser = "/apps/cca/mainagentuser_callout";
        }
        ModelAndView view = request(super.createRequestPageTempletResponse(mainagentuser));
        final User logined = super.getUser(request);
        final String orgi = logined.getOrgi();
        AgentUser agentUser = agentUserRepository.findByIdAndOrgi(id, orgi);

        if (agentUser != null) {
            view.addObject("curagentuser", agentUser);
            view.addObject("inviteData", OnlineUserProxy.consult(agentUser.getAppid(), agentUser.getOrgi()));
            List<AgentUserTask> agentUserTaskList = agentUserTaskRes.findByIdAndOrgi(id, orgi);
            if (agentUserTaskList.size() > 0) {
                AgentUserTask agentUserTask = agentUserTaskList.get(0);
                agentUserTask.setTokenum(0);
                agentUserTaskRes.save(agentUserTask);
            }

            if (StringUtils.isNotBlank(agentUser.getAgentserviceid())) {
                List<AgentServiceSummary> summarizes = this.serviceSummaryRes.findByAgentserviceidAndOrgi(
                        agentUser.getAgentserviceid(), orgi);
                if (summarizes.size() > 0) {
                    view.addObject("summary", summarizes.get(0));
                }
            }

            view.addObject(
                    "agentUserMessageList",
                    this.chatMessageRepository.findByUsessionAndOrgi(agentUser.getUserid(), orgi,
                                                                     new PageRequest(0, 20, Sort.Direction.DESC,
                                                                                     "updatetime")));
            AgentService agentService = null;
            if (StringUtils.isNotBlank(agentUser.getAgentserviceid())) {
                agentService = this.agentServiceRes.findOne(agentUser.getAgentserviceid());
                view.addObject("curAgentService", agentService);
                if (agentService != null) {
                    /**
                     * 获取关联数据
                     */
                    agentServiceProxy.processRelaData(logined.getId(), orgi, agentService, map);
                }
            }
            if (MainContext.ChannelType.WEBIM.toString().equals(agentUser.getChannel())) {
                OnlineUser onlineUser = onlineUserRes.findOne(agentUser.getUserid());
                if (onlineUser != null) {
                    if (onlineUser.getLogintime() != null) {
                        if (MainContext.OnlineUserStatusEnum.OFFLINE.toString().equals(onlineUser.getStatus())) {
                            onlineUser.setBetweentime(
                                    (int) (onlineUser.getUpdatetime().getTime() - onlineUser.getLogintime().getTime()));
                        } else {
                            onlineUser.setBetweentime(
                                    (int) (System.currentTimeMillis() - onlineUser.getLogintime().getTime()));
                        }
                    }
                    view.addObject("onlineUser", onlineUser);
                }

                view.addObject("serviceCount", Integer
                        .valueOf(this.agentServiceRes
                                         .countByUseridAndOrgiAndStatus(agentUser
                                                                                .getUserid(), orgi,
                                                                        MainContext.AgentUserStatusEnum.END
                                                                                .toString())));
            }

            SessionConfig sessionConfig = AutomaticServiceDist.initSessionConfig(super.getOrgi(request));

            view.addObject("sessionConfig", sessionConfig);
            if (sessionConfig.isOtherquickplay()) {
                view.addObject("topicList", OnlineUserProxy.search(null, orgi, super.getUser(request)));
            }

            view.addObject("tags", tagRes.findByOrgiAndTagtype(orgi, MainContext.ModelType.USER.toString()));
        }
        return view;
    }
}
