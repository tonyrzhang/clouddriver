/*
 * Copyright 2015 Netflix, Inc.
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

package com.netflix.spinnaker.mort.aws.provider.agent

import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.model.SecurityGroup
import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.amazoncomponents.security.AmazonClientProvider
import com.netflix.spectator.api.ExtendedRegistry
import com.netflix.spinnaker.amos.aws.NetflixAmazonCredentials
import com.netflix.spinnaker.cats.agent.AgentDataType
import com.netflix.spinnaker.cats.agent.CacheResult
import com.netflix.spinnaker.cats.agent.CachingAgent
import com.netflix.spinnaker.cats.agent.DefaultCacheResult
import com.netflix.spinnaker.cats.cache.CacheData
import com.netflix.spinnaker.cats.cache.DefaultCacheData
import com.netflix.spinnaker.cats.provider.ProviderCache
import com.netflix.spinnaker.clouddriver.cache.OnDemandAgent
import com.netflix.spinnaker.clouddriver.cache.OnDemandMetricsSupport
import com.netflix.spinnaker.mort.aws.cache.Keys
import com.netflix.spinnaker.mort.aws.provider.AwsInfrastructureProvider

import static com.netflix.spinnaker.cats.agent.AgentDataType.Authority.AUTHORITATIVE
import static com.netflix.spinnaker.mort.aws.cache.Keys.Namespace.SECURITY_GROUPS

import groovy.util.logging.Slf4j

@Slf4j
class AmazonSecurityGroupCachingAgent implements CachingAgent, OnDemandAgent {

  private static final String ON_DEMAND_TYPE = 'AmazonSecurityGroup'

  final AmazonClientProvider amazonClientProvider
  final NetflixAmazonCredentials account
  final String region
  final ObjectMapper objectMapper
  final ExtendedRegistry extendedRegistry

  final OnDemandMetricsSupport metricsSupport

  static final Set<AgentDataType> types = Collections.unmodifiableSet([
    AUTHORITATIVE.forType(SECURITY_GROUPS.ns)
  ] as Set)

  AmazonSecurityGroupCachingAgent(AmazonClientProvider amazonClientProvider, NetflixAmazonCredentials account, String region, ObjectMapper objectMapper, ExtendedRegistry extendedRegistry) {
    this.amazonClientProvider = amazonClientProvider
    this.account = account
    this.region = region
    this.objectMapper = objectMapper
    this.extendedRegistry = extendedRegistry
    this.metricsSupport = new OnDemandMetricsSupport(extendedRegistry, this, ON_DEMAND_TYPE)
  }

  @Override
  String getProviderName() {
    AwsInfrastructureProvider.PROVIDER_NAME
  }

  @Override
  String getAgentType() {
    "${account.name}/${region}/${AmazonSecurityGroupCachingAgent.simpleName}"
  }

  @Override
  Collection<AgentDataType> getProvidedDataTypes() {
    return types
  }

  @Override
  String getOnDemandAgentType() {
    getAgentType()
  }

  @Override
  OnDemandAgent.OnDemandResult handle(ProviderCache providerCache, Map<String, ? extends Object> data) {
    if (account.name != data.account) {
      return null
    }

    if (region != data.region) {
      return null
    }

    def securityGroups = metricsSupport.readData {
      def ec2 = amazonClientProvider.getAmazonEC2(account, region, true)
      return getSecurityGroups(ec2)
    }

    CacheResult result = metricsSupport.transformData { buildCacheResult(providerCache, securityGroups) }

    new OnDemandAgent.OnDemandResult(sourceAgentType: getAgentType(), authoritativeTypes: [SECURITY_GROUPS.ns], cacheResult: result)
  }

  @Override
  boolean handles(String type) {
    type == "AmazonSecurityGroup"
  }


  @Override
  CacheResult loadData(ProviderCache providerCache) {
    def ec2 = amazonClientProvider.getAmazonEC2(account, region)
    buildCacheResult(providerCache, getSecurityGroups(ec2))
  }

  private List<SecurityGroup> getSecurityGroups(AmazonEC2 amazonEC2) {
    log.info("Describing items in ${agentType}")
    amazonEC2.describeSecurityGroups().securityGroups
  }

  private CacheResult buildCacheResult(ProviderCache providerCache, List<SecurityGroup> securityGroups) {
    List<CacheData> data = securityGroups.collect { SecurityGroup securityGroup ->
      Map<String, Object> attributes = objectMapper.convertValue(securityGroup, AwsInfrastructureProvider.ATTRIBUTES)
      new DefaultCacheData(Keys.getSecurityGroupKey(securityGroup.groupName, securityGroup.groupId, region, account.name, securityGroup.vpcId),
        attributes,
        [:])
    }
    log.info("Caching ${data.size()} items in ${agentType}")
    new DefaultCacheResult([(SECURITY_GROUPS.ns): data])
  }
}