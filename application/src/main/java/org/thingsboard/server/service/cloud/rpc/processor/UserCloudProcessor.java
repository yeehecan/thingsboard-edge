/**
 * ThingsBoard, Inc. ("COMPANY") CONFIDENTIAL
 *
 * Copyright © 2016-2022 ThingsBoard, Inc. All Rights Reserved.
 *
 * NOTICE: All information contained herein is, and remains
 * the property of ThingsBoard, Inc. and its suppliers,
 * if any.  The intellectual and technical concepts contained
 * herein are proprietary to ThingsBoard, Inc.
 * and its suppliers and may be covered by U.S. and Foreign Patents,
 * patents in process, and are protected by trade secret or copyright law.
 *
 * Dissemination of this information or reproduction of this material is strictly forbidden
 * unless prior written permission is obtained from COMPANY.
 *
 * Access to the source code contained herein is hereby forbidden to anyone except current COMPANY employees,
 * managers or contractors who have executed Confidentiality and Non-disclosure agreements
 * explicitly covering such access.
 *
 * The copyright notice above does not evidence any actual or intended publication
 * or disclosure  of  this source code, which includes
 * information that is confidential and/or proprietary, and is a trade secret, of  COMPANY.
 * ANY REPRODUCTION, MODIFICATION, DISTRIBUTION, PUBLIC  PERFORMANCE,
 * OR PUBLIC DISPLAY OF OR THROUGH USE  OF THIS  SOURCE CODE  WITHOUT
 * THE EXPRESS WRITTEN CONSENT OF COMPANY IS STRICTLY PROHIBITED,
 * AND IN VIOLATION OF APPLICABLE LAWS AND INTERNATIONAL TREATIES.
 * THE RECEIPT OR POSSESSION OF THIS SOURCE CODE AND/OR RELATED INFORMATION
 * DOES NOT CONVEY OR IMPLY ANY RIGHTS TO REPRODUCE, DISCLOSE OR DISTRIBUTE ITS CONTENTS,
 * OR TO MANUFACTURE, USE, OR SELL ANYTHING THAT IT  MAY DESCRIBE, IN WHOLE OR IN PART.
 */
package org.thingsboard.server.service.cloud.rpc.processor;

import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.audit.ActionType;
import org.thingsboard.server.common.data.cloud.CloudEventType;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.common.data.security.UserCredentials;
import org.thingsboard.server.dao.model.ModelConstants;
import org.thingsboard.server.dao.user.UserService;
import org.thingsboard.server.dao.user.UserServiceImpl;
import org.thingsboard.server.gen.edge.v1.UpdateMsgType;
import org.thingsboard.server.gen.edge.v1.UserCredentialsUpdateMsg;
import org.thingsboard.server.gen.edge.v1.UserUpdateMsg;

import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Component
@Slf4j
public class UserCloudProcessor extends BaseCloudProcessor {

    private final Lock userCreationLock = new ReentrantLock();

    @Autowired
    private UserService userService;

    public ListenableFuture<Void> processUserMsgFromCloud(TenantId tenantId,
                                                          UserUpdateMsg userUpdateMsg,
                                                          Long queueStartTs) {
        UserId userId = new UserId(new UUID(userUpdateMsg.getIdMSB(), userUpdateMsg.getIdLSB()));
        switch (userUpdateMsg.getMsgType()) {
            case ENTITY_CREATED_RPC_MESSAGE:
            case ENTITY_UPDATED_RPC_MESSAGE:
                userCreationLock.lock();
                try {
                    boolean created = false;
                    User user = userService.findUserById(tenantId, userId);
                    if (user == null) {
                        user = new User();
                        user.setTenantId(tenantId);
                        user.setId(userId);
                        user.setCreatedTime(Uuids.unixTimestamp(userId.getId()));
                        created = true;
                    }
                    user.setEmail(userUpdateMsg.getEmail());
                    user.setAuthority(Authority.valueOf(userUpdateMsg.getAuthority()));
                    if (userUpdateMsg.hasFirstName()) {
                        user.setFirstName(userUpdateMsg.getFirstName());
                    }
                    if (userUpdateMsg.hasLastName()) {
                        user.setLastName(userUpdateMsg.getLastName());
                    }
                    if (userUpdateMsg.hasAdditionalInfo()) {
                        user.setAdditionalInfo(JacksonUtil.toJsonNode(userUpdateMsg.getAdditionalInfo()));
                    }
                    safeSetCustomerId(userUpdateMsg, user);
                    User savedUser = userService.saveUser(user, false);
                    if (created) {
                        UserCredentials userCredentials = new UserCredentials();
                        userCredentials.setEnabled(false);
                        userCredentials.setActivateToken(RandomStringUtils.randomAlphanumeric(UserServiceImpl.DEFAULT_TOKEN_LENGTH));
                        userCredentials.setUserId(new UserId(savedUser.getUuidId()));
                        userService.saveUserCredentialsAndPasswordHistory(user.getTenantId(), userCredentials);
                    }
                } finally {
                    userCreationLock.unlock();
                }
                break;
            case ENTITY_DELETED_RPC_MESSAGE:
                User userToDelete = userService.findUserById(tenantId, userId);
                if (userToDelete != null) {
                    userService.deleteUser(tenantId, userToDelete.getId());
                }
                break;
            case UNRECOGNIZED:
                log.error("Unsupported msg type");
                return Futures.immediateFailedFuture(new RuntimeException("Unsupported msg type " + userUpdateMsg.getMsgType()));
        }
        ListenableFuture<Void> aDRF = Futures.transform(
                requestForAdditionalData(tenantId, userUpdateMsg.getMsgType(), userId, queueStartTs), future -> null, dbCallbackExecutor);

        ListenableFuture<ListenableFuture<Void>> t = Futures.transform(aDRF, aDR -> {
            if (UpdateMsgType.ENTITY_CREATED_RPC_MESSAGE.equals(userUpdateMsg.getMsgType()) ||
                    UpdateMsgType.ENTITY_UPDATED_RPC_MESSAGE.equals(userUpdateMsg.getMsgType())) {
                saveCloudEvent(tenantId, CloudEventType.USER, ActionType.CREDENTIALS_REQUEST, userId, null);
            }
            return Futures.immediateFuture(null);
        }, dbCallbackExecutor);

        return Futures.transform(t, tt -> null, dbCallbackExecutor);
    }

    private void safeSetCustomerId(UserUpdateMsg userUpdateMsg, User user) {
        CustomerId customerId = safeGetCustomerId(userUpdateMsg.getCustomerIdMSB(),
                userUpdateMsg.getCustomerIdLSB());
        if (customerId == null) {
            customerId = new CustomerId(ModelConstants.NULL_UUID);
        }
        user.setCustomerId(customerId);
    }

    public ListenableFuture<Void> processUserCredentialsMsgFromCloud(TenantId tenantId, UserCredentialsUpdateMsg userCredentialsUpdateMsg) {
        UserId userId = new UserId(new UUID(userCredentialsUpdateMsg.getUserIdMSB(), userCredentialsUpdateMsg.getUserIdLSB()));
        ListenableFuture<User> userFuture = userService.findUserByIdAsync(tenantId, userId);
        return Futures.transform(userFuture, user -> {
            if (user != null) {
                UserCredentials userCredentials = userService.findUserCredentialsByUserId(tenantId, user.getId());
                userCredentials.setEnabled(userCredentialsUpdateMsg.getEnabled());
                userCredentials.setPassword(userCredentialsUpdateMsg.getPassword());
                userCredentials.setActivateToken(null);
                userCredentials.setResetToken(null);
                userService.saveUserCredentials(tenantId, userCredentials, false);
            }
            return null;
        }, dbCallbackExecutor);
    }
}
