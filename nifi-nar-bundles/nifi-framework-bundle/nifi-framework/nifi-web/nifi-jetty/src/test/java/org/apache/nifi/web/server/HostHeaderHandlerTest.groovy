/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.web.server

import org.apache.nifi.properties.StandardNiFiProperties
import org.apache.nifi.util.NiFiProperties
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@RunWith(JUnit4.class)
class HostHeaderHandlerTest extends GroovyTestCase {
    private static final Logger logger = LoggerFactory.getLogger(HostHeaderHandlerTest.class)

    private static final String DEFAULT_HOSTNAME = "nifi.apache.org"
    private static final String ACTUAL_HOSTNAME = InetAddress.getLocalHost().getHostName().toLowerCase()
    private static final int DEFAULT_PORT = 8080
    private static final List<String> DEFAULT_HOSTS_1_5_0 = [DEFAULT_HOSTNAME, "localhost", ACTUAL_HOSTNAME]
    private static
    final List<String> DEFAULT_HOSTS_AND_PORTS_1_5_0 = DEFAULT_HOSTS_1_5_0.collectMany { it -> [it, "${it}:${DEFAULT_PORT}"] }

    // Post 1.5.0 list
    private static final String ACTUAL_IP = InetAddress.getLocalHost().getHostAddress()
    private static final String LOOPBACK_IP = InetAddress.getLoopbackAddress().getHostAddress()
    private static
    final List<String> DEFAULT_HOSTS = DEFAULT_HOSTS_1_5_0 - DEFAULT_HOSTNAME + ["[::1]", "127.0.0.1", ACTUAL_IP, LOOPBACK_IP]
    private static
    final List<String> DEFAULT_HOSTS_AND_PORTS = DEFAULT_HOSTS.collectMany { it -> [it, "${it}:${DEFAULT_PORT}"] }

    @BeforeClass
    static void setUpOnce() throws Exception {
        logger.metaClass.methodMissing = { String name, args ->
            logger.info("[${name?.toUpperCase()}] ${(args as List).join(" ")}")
        }
    }

    @Before
    void setUp() throws Exception {
    }

    @After
    void tearDown() throws Exception {
    }

    @Test
    void testConstructorShouldAcceptSingleValues() throws Exception {
        // Arrange
        String hostname = DEFAULT_HOSTNAME
        int port = DEFAULT_PORT
        logger.info("Hostname: ${hostname} | port: ${port}")

        // Act
        HostHeaderHandler handler = new HostHeaderHandler(hostname, port)
        logger.info("Handler: ${handler}")

        // Assert
        assert handler.hostHeaderIsValid(hostname)
        assert handler.hostHeaderIsValid("${hostname}:${port}")
    }

    /**
     * The feature was introduced in Apache NiFi 1.5.0 but the behavior was changed following that release to include the actual IP address of the server, IPv6 ::1, and 127.0.0.1.
     * @throws Exception
     */
    @Test
    void testShouldHandle_1_5_0_DefaultValues() throws Exception {
        // Arrange
        String hostname = DEFAULT_HOSTNAME
        int port = DEFAULT_PORT
        logger.info("Hostname: ${hostname} | port: ${port}")

        // Act
        HostHeaderHandler handler = new HostHeaderHandler(hostname, port)
        logger.info("Handler: ${handler}")

        // Assert
        DEFAULT_HOSTS_AND_PORTS_1_5_0.each { String host ->
            logger.debug("Validating ${host}")
            assert handler.hostHeaderIsValid(host)
        }
    }

    @Test
    void testNewConstructorShouldHandleCurrentDefaultValues() throws Exception {
        // Arrange
        String hostname = DEFAULT_HOSTNAME
        int port = DEFAULT_PORT
        logger.info("Hostname: ${hostname} | port: ${port}")

        NiFiProperties mockProperties = [
                getSslPort: { -> null },
                getPort   : { -> DEFAULT_PORT },
        ] as StandardNiFiProperties

        Properties rawProps = new Properties()
        rawProps.putAll([
                (NiFiProperties.WEB_HTTPS_HOST): DEFAULT_HOSTNAME,
                (NiFiProperties.WEB_HTTPS_PORT): DEFAULT_PORT,
//                (NiFiProperties.WEB_HTTP_PORT) : null
        ])
        NiFiProperties simpleProperties = new StandardNiFiProperties(rawProps)

        // Act
        HostHeaderHandler handler = new HostHeaderHandler(simpleProperties)
        logger.info("Handler: ${handler}")

        // Assert
        DEFAULT_HOSTS_AND_PORTS.each { String host ->
            logger.debug("Validating ${host}")
            assert handler.hostHeaderIsValid(host)
        }
    }

    @Test
    void testShouldParseCustomHostnames() throws Exception {
        // Arrange
        String hostname = DEFAULT_HOSTNAME
        int port = DEFAULT_PORT
        logger.info("Hostname: ${hostname} | port: ${port}")

        List<String> otherHosts = ["someotherhost.com:9999", "yetanotherbadhost.com", "10.10.10.1:1234", "100.100.100.1"]
        String concatenatedHosts = otherHosts.join(",")

        Properties rawProps = new Properties()
        rawProps.putAll([
                (NiFiProperties.WEB_HTTPS_HOST): DEFAULT_HOSTNAME,
                (NiFiProperties.WEB_HTTPS_PORT): "${DEFAULT_PORT}",
                (NiFiProperties.WEB_PROXY_HOST): concatenatedHosts
        ])
        NiFiProperties simpleProperties = new StandardNiFiProperties(rawProps)

        HostHeaderHandler handler = new HostHeaderHandler(simpleProperties)
        logger.info("Handler: ${handler}")

        // Act
        List<String> customHostnames = handler.parseCustomHostnames(simpleProperties)
        logger.info("Parsed custom hostnames: ${customHostnames}")

        // Assert
        assert customHostnames.size() == otherHosts.size()
        otherHosts.each { String host ->
            logger.debug("Checking ${host}")
            assert customHostnames.contains(host)
        }
    }
}
