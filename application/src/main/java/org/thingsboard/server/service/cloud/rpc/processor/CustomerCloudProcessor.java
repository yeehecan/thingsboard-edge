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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.Customer;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.dao.customer.CustomerService;
import org.thingsboard.server.gen.edge.v1.CustomerUpdateMsg;

import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Component
@Slf4j
public class CustomerCloudProcessor extends BaseCloudProcessor {

    private final Lock customerCreationLock = new ReentrantLock();

    @Autowired
    private CustomerService customerService;

    public ListenableFuture<Void> processCustomerMsgFromCloud(TenantId tenantId, CustomerUpdateMsg customerUpdateMsg) {
        CustomerId customerId = new CustomerId(new UUID(customerUpdateMsg.getIdMSB(), customerUpdateMsg.getIdLSB()));
        switch (customerUpdateMsg.getMsgType()) {
            case ENTITY_CREATED_RPC_MESSAGE:
            case ENTITY_UPDATED_RPC_MESSAGE:
                customerCreationLock.lock();
                try {
                    Customer customer = customerService.findCustomerById(tenantId, customerId);
                    if (customer == null) {
                        customer = new Customer();
                        customer.setId(customerId);
                        customer.setCreatedTime(Uuids.unixTimestamp(customerId.getId()));
                        customer.setTenantId(tenantId);
                    }
                    customer.setTitle(customerUpdateMsg.getTitle());
                    if (customerUpdateMsg.hasCountry()) {
                        customer.setCountry(customerUpdateMsg.getCountry());
                    }
                    if (customerUpdateMsg.hasState()) {
                        customer.setState(customerUpdateMsg.getState());
                    }
                    if (customerUpdateMsg.hasCity()) {
                        customer.setCity(customerUpdateMsg.getCity());
                    }
                    if (customerUpdateMsg.hasAddress()) {
                        customer.setAddress(customerUpdateMsg.getAddress());
                    }
                    if (customerUpdateMsg.hasAddress2()) {
                        customer.setAddress2(customerUpdateMsg.getAddress2());
                    }
                    if (customerUpdateMsg.hasZip()) {
                        customer.setZip(customerUpdateMsg.getZip());
                    }
                    if (customerUpdateMsg.hasPhone()) {
                        customer.setPhone(customerUpdateMsg.getPhone());
                    }
                    if (customerUpdateMsg.hasEmail()) {
                        customer.setEmail(customerUpdateMsg.getEmail());
                    }
                    if (customerUpdateMsg.hasAdditionalInfo()) {
                        customer.setAdditionalInfo(JacksonUtil.toJsonNode(customerUpdateMsg.getAdditionalInfo()));
                    }
                    customerService.saveCustomer(customer, false);
                } finally {
                    customerCreationLock.unlock();
                }
                break;
            case ENTITY_DELETED_RPC_MESSAGE:
                Customer customerById = customerService.findCustomerById(tenantId, customerId);
                if (customerById != null) {
                   customerService.deleteCustomer(tenantId, customerId);
                }
                break;
            case UNRECOGNIZED:
                log.error("Unsupported msg type");
                return Futures.immediateFailedFuture(new RuntimeException("Unsupported msg type " + customerUpdateMsg.getMsgType()));
        }
        return Futures.immediateFuture(null);
    }
}
