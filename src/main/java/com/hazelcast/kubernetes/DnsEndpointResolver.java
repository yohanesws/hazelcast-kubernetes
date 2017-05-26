/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.kubernetes;

import com.hazelcast.config.NetworkConfig;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.Address;
import com.hazelcast.spi.discovery.DiscoveryNode;
import com.hazelcast.spi.discovery.SimpleDiscoveryNode;
import org.xbill.DNS.ExtendedResolver;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Record;
import org.xbill.DNS.SRVRecord;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class DnsEndpointResolver
        extends HazelcastKubernetesDiscoveryStrategy.EndpointResolver {

    private final String serviceDns;
    private final int serviceDnsTimeout;
    private final String otherMember;

    DnsEndpointResolver(ILogger logger, String serviceDns, int serviceDnsTimeout, String otherMember) {
        super(logger);
        this.serviceDns = serviceDns;
        this.serviceDnsTimeout = serviceDnsTimeout;
        this.otherMember = otherMember;
    }


    List<DiscoveryNode> resolve() {
        try {
            Lookup lookup = buildLookup();
            Record[] records = lookup.run();

            if (lookup.getResult() != Lookup.SUCCESSFUL) {
                logger.warning("DNS lookup for serviceDns '" + serviceDns + "' failed");
                return Collections.emptyList();
            }

            // We collect all records as some instances seem to return multiple dns records
            Set<Address> addresses = new HashSet<Address>();
            for (Record record : records) {
                // Example:
                // nslookup u219692-hazelcast.u219692-hazelcast.svc.cluster.local 172.30.0.1
                //      Server:         172.30.0.1
                //      Address:        172.30.0.1#53
                //
                //      Name:   u219692-hazelcast.u219692-hazelcast.svc.cluster.local
                //      Address: 10.1.2.8
                //      Name:   u219692-hazelcast.u219692-hazelcast.svc.cluster.local
                //      Address: 10.1.5.28
                //      Name:   u219692-hazelcast.u219692-hazelcast.svc.cluster.local
                //      Address: 10.1.9.33
                SRVRecord srv = (SRVRecord) record;
                InetAddress[] inetAddress = getAllAddresses(srv);
                int port = getHazelcastPort(srv.getPort());

                for (InetAddress i : inetAddress) {
                    Address address = new Address(i, port);

                    // If address is newly discovered and logger is finest, emit log entry
                    if (addresses.add(address) && logger.isFinestEnabled()) {
                        logger.finest("Found node service with address: " + address);
                    }
                }
            }

            if (otherMember != null) {
            String[] members = otherMember.split(",");
              for (String member : members) {
                  String[] nodes = member.split(":");
                  if (nodes.length > 1) {
                    InetAddress nodeIp = InetAddress.getByName(nodes[0]);
                    int nodePort = getHazelcastPort(Integer.parseInt(nodes[1]));
                    Address extraAddress = new Address(nodeIp, nodePort);

                    if (addresses.add(extraAddress) && logger.isFinestEnabled()) {
                        logger.finest("Found node service with address: " + extraAddress);
                    }
                  }
              }
            }



            if (addresses.size() == 0) {
                logger.warning("Could not find any service for serviceDns '" + serviceDns + "'");
                return Collections.emptyList();
            }

            return asDiscoveredNodes(addresses);

        } catch (TextParseException e) {
            throw new RuntimeException("Could not resolve services via DNS", e);
        } catch (UnknownHostException e) {
            throw new RuntimeException("Could not resolve services via DNS", e);
        }
    }

    private Lookup buildLookup()
            throws TextParseException, UnknownHostException {

        ExtendedResolver resolver = new ExtendedResolver();
        resolver.setTimeout(serviceDnsTimeout);

        Lookup lookup = new Lookup(serviceDns, Type.SRV);
        lookup.setResolver(resolver);

        // Avoid caching temporary DNS lookup failures indefinitely in global cache
        lookup.setCache(null);

        return lookup;
    }

    private List<DiscoveryNode> asDiscoveredNodes(Set<Address> addresses) {
        List<DiscoveryNode> discoveryNodes = new ArrayList<DiscoveryNode>();
        for (Address address : addresses) {
            discoveryNodes.add(new SimpleDiscoveryNode(address));
        }
        return discoveryNodes;
    }

    private int getHazelcastPort(int port) {
        if (port > 0) {
            return port;
        }
        return NetworkConfig.DEFAULT_PORT;
    }

    private InetAddress[] getAllAddresses(SRVRecord srv)
            throws UnknownHostException {

        try {
            return org.xbill.DNS.Address.getAllByName(srv.getTarget().canonicalize().toString(true));
        } catch (UnknownHostException e) {
            logger.severe("Parsing DNS records failed", e);
            throw e;
        }
    }
}
