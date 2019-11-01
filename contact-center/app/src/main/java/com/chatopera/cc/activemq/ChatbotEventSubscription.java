/*
 * Copyright (C) 2019 Chatopera Inc, All rights reserved.
 * <https://www.chatopera.com>
 * This software and related documentation are provided under a license agreement containing
 * restrictions on use and disclosure and are protected by intellectual property laws.
 * Except as expressly permitted in your license agreement or allowed by law, you may not use,
 * copy, reproduce, translate, broadcast, modify, license, transmit, distribute, exhibit, perform,
 * publish, or display any part, in any form, or by any means. Reverse engineering, disassembly,
 * or decompilation of this software, unless required by law for interoperability, is prohibited.
 */

package com.chatopera.cc.activemq;

import com.chatopera.bot.exception.ChatbotException;
import com.chatopera.cc.basic.MainContext;
import com.chatopera.cc.cache.Cache;
import com.chatopera.cc.handler.api.request.RestUtils;
import com.chatopera.cc.socketio.message.ChatMessage;
import com.chatopera.cc.proxy.ChatbotProxy;
import com.chatopera.cc.model.AgentUser;
import com.chatopera.cc.model.Chatbot;
import com.chatopera.cc.persistence.repository.AgentUserRepository;
import com.chatopera.cc.persistence.repository.ChatbotRepository;
import com.chatopera.cc.basic.Constants;
import com.chatopera.cc.util.SerializeUtil;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;

/**
 * 发送消息给聊天机器人并处理返回结果
 */
@Component
public class ChatbotEventSubscription {
    private final static Logger logger = LoggerFactory.getLogger(ChatbotEventSubscription.class);

    @Autowired
    private Cache cache;

    @Autowired
    private AgentUserRepository agentUserRes;

    @Autowired
    private ChatbotRepository chatbotRes;

    @Value("${bot.baseurl}")
    private static String botBaseUrl;

    @Autowired
    private ChatbotProxy chatbotProxy;

    /**
     * 接收发送消息给聊天机器人的请求
     *
     * @param payload
     */
    @JmsListener(destination = Constants.INSTANT_MESSAGING_MQ_QUEUE_CHATBOT, containerFactory = "jmsListenerContainerQueue")
    public void onMessage(final String payload) {
        ChatMessage message = SerializeUtil.deserialize(payload);
        try {
            chat(message);
        } catch (MalformedURLException e) {
            logger.error("[onMessage] error", e);
        } catch (ChatbotException e) {
            logger.error("[onMessage] error", e);
        }
    }


    private void chat(final ChatMessage request) throws MalformedURLException, ChatbotException, JSONException {
        Chatbot c = chatbotRes
                .findOne(request.getAiid());

        logger.info(
                "[chat] chat request baseUrl {}, chatbot {}, fromUserId {}, textMessage {}", botBaseUrl, c.getName(),
                request.getUserid(), request.getMessage());
        // Get response from Conversational Engine.
        com.chatopera.bot.sdk.Chatbot bot = new com.chatopera.bot.sdk.Chatbot(
                c.getClientId(), c.getSecret(), botBaseUrl);
        JSONObject result = bot.conversation(request.getUserid(), request.getMessage());

        // parse response
        if (result != null) {
            logger.info("[chat] chat response {}", result.toString());
            if (result.getInt(RestUtils.RESP_KEY_RC) == 0) {
                // reply
                JSONObject data = result.getJSONObject("data");
                ChatMessage resp = new ChatMessage();
                resp.setCalltype(MainContext.CallType.OUT.toString());
                resp.setAppid(resp.getAppid());
                resp.setOrgi(request.getOrgi());
                resp.setAiid(request.getAiid());
                resp.setMessage(data.getString("string"));
                resp.setTouser(request.getUserid());
                resp.setAgentserviceid(request.getAgentserviceid());
                resp.setMsgtype(request.getMsgtype());
                resp.setUserid(request.getUserid());
                resp.setType(request.getType());
                resp.setChannel(request.getChannel());
                if (data.has("params")) {
                    resp.setExpmsg(data.get("params").toString());
                }
                resp.setContextid(request.getContextid());
                resp.setSessionid(request.getSessionid());
                resp.setUsession(request.getUsession());
                resp.setUsername(c.getName());
                resp.setUpdatetime(System.currentTimeMillis());

                // 更新聊天机器人累计值
                updateAgentUserWithRespData(request.getUserid(), request.getOrgi(), data);
                // 保存并发送
                chatbotProxy.saveAndPublish(resp);
            } else {
                logger.warn("[chat] can not get expected response {}", result.toString());
            }
        }
    }

    /**
     * 根据聊天机器人返回数据更新agentUser
     *
     * @param userid
     * @param data
     */
    private void updateAgentUserWithRespData(final String userid, final String orgi, final JSONObject data) throws JSONException {
        cache.findOneAgentUserByUserIdAndOrgi(userid, orgi).ifPresent(p -> {
            p.setChatbotround(p.getChatbotround() + 1);
            if (data.has("logic_is_unexpected") && data.getBoolean("logic_is_unexpected")) {
                p.setChatbotlogicerror(p.getChatbotlogicerror() + 1);
            }
            agentUserRes.save(p);
        });

    }

}
