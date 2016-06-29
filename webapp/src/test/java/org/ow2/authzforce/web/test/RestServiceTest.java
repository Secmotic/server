/**
 * Copyright (C) 2015-2015 Thales Services SAS. All rights reserved. No warranty, explicit or implicit, provided.
 */
package org.ow2.authzforce.web.test;

/**
 *
 *
 */
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.validation.Schema;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.deploy.ContextEnvironment;
import org.apache.catalina.deploy.NamingResources;
import org.apache.catalina.startup.Tomcat;
import org.apache.cxf.interceptor.FIStaxInInterceptor;
import org.apache.cxf.interceptor.FIStaxOutInterceptor;
import org.apache.cxf.interceptor.Interceptor;
import org.apache.cxf.interceptor.LoggingInInterceptor;
import org.apache.cxf.interceptor.LoggingOutInterceptor;
import org.apache.cxf.jaxrs.client.ClientConfiguration;
import org.apache.cxf.jaxrs.client.JAXRSClientFactory;
import org.apache.cxf.jaxrs.client.WebClient;
import org.apache.cxf.jaxrs.provider.JAXBElementProvider;
import org.apache.cxf.jaxrs.utils.schemas.SchemaHandler;
import org.ow2.authzforce.core.pdp.impl.PdpModelHandler;
import org.ow2.authzforce.core.xmlns.test.TestAttributeProvider;
import org.ow2.authzforce.pap.dao.flatfile.FlatFileDAOUtils;
import org.ow2.authzforce.pap.dao.flatfile.xmlns.DomainProperties;
import org.ow2.authzforce.rest.api.jaxrs.DomainsResource;
import org.ow2.authzforce.rest.api.xmlns.Resources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.testng.AbstractTestNGSpringContextTests;

import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;

import oasis.names.tc.xacml._3_0.core.schema.wd_17.PolicySet;
import oasis.names.tc.xacml._3_0.core.schema.wd_17.Request;
import oasis.names.tc.xacml._3_0.core.schema.wd_17.Target;

@ContextConfiguration(locations = { "classpath:META-INF/spring/client.xml" })
abstract class RestServiceTest extends AbstractTestNGSpringContextTests
{
	protected static final Random PRNG = new Random();

	/*
	 * Start embedded server on a random port between 9000 and 9999 (inclusive) to avoid conflict with another parallel test run
	 */
	private static final AtomicInteger EMBEDDED_SERVER_PORT = new AtomicInteger(9000 + PRNG.nextInt(1000));
	private static final String EMBEDDED_APP_CONTEXT_PATH = "/";

	protected static final AtomicBoolean IS_EMBEDDED_SERVER_STARTED = new AtomicBoolean(false);

	private static final int XML_MAX_CHILD_ELEMENTS = 20;

	/*
	 * Below that value getWADL() test fails because WADL depth too big
	 */
	private static final int XML_MAX_ELEMENT_DEPTH = 10;
	private static final int XML_MAX_ATTRIBUTE_COUNT = 100;
	protected static final int XML_MAX_TEXT_LENGTH = 1000;

	/*
	 * For maxAttributeSize = 500 in JAXRS server configuration, exception raised only when chars.length > 911! WHY? Possible issue with woodstox library. FIXME: report this issue to CXF/Woodstox
	 */
	private static final int XML_MAX_ATTRIBUTE_SIZE = 500;
	protected static final int XML_MAX_ATTRIBUTE_SIZE_EFFECTIVE = 911;

	protected static final File DOMAINS_DIR = new File("target/server/conf/authzforce-ce/domains");

	protected static final File XACML_SAMPLES_DIR = new File("src/test/resources/xacml.samples");
	static
	{
		if (!XACML_SAMPLES_DIR.exists())
		{
			throw new RuntimeException("XACML SAMPLES DIRECTORY NOT FOUND: " + XACML_SAMPLES_DIR);
		}
	}

	static final File XACML_IIIG301_PDP_TEST_DIR = new File(RestServiceTest.XACML_SAMPLES_DIR, "IIIG301");
	static
	{
		if (!XACML_IIIG301_PDP_TEST_DIR.exists())
		{
			throw new RuntimeException("XACML PDP TEST DIRECTORY NOT FOUND: " + XACML_IIIG301_PDP_TEST_DIR);
		}
	}

	static final File XACML_POLICYREFS_PDP_TEST_DIR = new File(RestServiceTest.XACML_SAMPLES_DIR, "pdp/PolicyReference.Valid");
	static
	{
		if (!XACML_POLICYREFS_PDP_TEST_DIR.exists())
		{
			throw new RuntimeException("XACML POLICYREFS PDP TEST DIRECTORY NOT FOUND: " + XACML_POLICYREFS_PDP_TEST_DIR);
		}
	}

	protected static final Path SAMPLE_DOMAIN_DIR = Paths.get("src/test/resources/domain.samples/A0bdIbmGEeWhFwcKrC9gSQ");
	static
	{
		if (!Files.exists(SAMPLE_DOMAIN_DIR))
		{
			throw new RuntimeException("SAMPLE DOMAIN DIRECTORY NOT FOUND: " + SAMPLE_DOMAIN_DIR);
		}
	}

	protected static final String SAMPLE_DOMAIN_ID = SAMPLE_DOMAIN_DIR.getFileName().toString();
	protected static final Path SAMPLE_DOMAIN_COPY_DIR = new File(DOMAINS_DIR, SAMPLE_DOMAIN_ID).toPath();

	/*
	 * JAXB context for (un)marshalling XACML
	 */
	protected static final JAXBContext JAXB_CTX;

	static
	{
		try
		{
			JAXB_CTX = JAXBContext.newInstance(PolicySet.class, Request.class, Resources.class, DomainProperties.class, TestAttributeProvider.class);
		} catch (JAXBException e)
		{
			throw new RuntimeException("Error instantiating JAXB context for XML to Java binding", e);
		}
	}

	protected static final DateFormat UTC_DATE_WITH_MILLIS_FORMATTER = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS ('UTC')");
	static
	{
		UTC_DATE_WITH_MILLIS_FORMATTER.setTimeZone(TimeZone.getTimeZone("UTC"));
	}

	/**
	 * XACML request filename
	 */
	protected final static String REQUEST_FILENAME = "request.xml";

	/**
	 * XACML policy filename used by default when no PDP configuration file found, i.e. no file named "pdp.xml" exists in the test directory
	 */
	protected final static String TEST_POLICY_FILENAME = "policy.xml";

	/**
	 * Expected XACML response filename
	 */
	protected final static String EXPECTED_RESPONSE_FILENAME = "response.xml";

	protected static final String TEST_REF_POLICIES_DIRECTORY_NAME = "refPolicies";

	protected static final String TEST_ATTRIBUTE_PROVIDER_FILENAME = "attributeProvider.xml";

	protected static final String TEST_DEFAULT_POLICYSET_FILENAME = "policy.xml";

	public final static String DOMAIN_POLICIES_DIRNAME = "policies";
	public final static String DOMAIN_PDP_CONF_FILENAME = "pdp.xml";

	protected static final String FASTINFOSET_MEDIA_TYPE = "application/fastinfoset";

	public final static String DOMAIN_PROPERTIES_FILENAME = "properties.xml";

	private static final Logger LOGGER = LoggerFactory.getLogger(RestServiceTest.class);

	protected static PolicySet createDumbPolicySet(String policyId, String version)
	{
		return createDumbPolicySet(policyId, version, null);
	}

	protected static PolicySet createDumbPolicySet(String policyId, String version, String description)
	{
		return new PolicySet(description, null, null, new Target(null), null, null, null, policyId, version, "urn:oasis:names:tc:xacml:3.0:policy-combining-algorithm:deny-unless-permit",
				BigInteger.ZERO);
	}

	@Autowired
	@Qualifier("pdpModelHandler")
	protected PdpModelHandler pdpModelHandler;

	@Autowired
	@Qualifier("clientApiSchemaHandler")
	protected SchemaHandler clientApiSchemaHandler;

	private Tomcat embeddedServer = null;

	@Autowired
	@Qualifier("clientJaxbProvider")
	private JAXBElementProvider<?> clientJaxbProvider;
	
	@Autowired
	@Qualifier("clientJsonProvider")
	private JacksonJsonProvider clientJsonJaxbProvider;

	@Autowired
	@Qualifier("clientJaxbProviderFI")
	private JAXBElementProvider<?> clientJaxbProviderFI;

	protected Unmarshaller unmarshaller = null;

	protected DomainsResource domainsAPIProxyClient = null;

	/**
	 * 
	 * @param port
	 *            server port, dynamically allocated if negative
	 * @param enableFastInfoset
	 * @param domainSyncIntervalSec
	 * @param addSampleDomain
	 * @return
	 * @throws ServletException
	 * @throws IllegalArgumentException
	 * @throws IOException
	 * @throws LifecycleException
	 */
	private static Tomcat startServer(int port, boolean enableFastInfoset, int domainSyncIntervalSec, boolean addSampleDomain) throws ServletException, IllegalArgumentException, IOException,
			LifecycleException
	{
		/*
		 * Make sure the domains directory exists and is empty
		 */
		if (DOMAINS_DIR.exists())
		{
			// delete to start clean
			FlatFileDAOUtils.deleteDirectory(DOMAINS_DIR.toPath(), 4);
		}

		DOMAINS_DIR.mkdirs();

		if (addSampleDomain)
		{
			// Add sample domain directory
			// create domain directory on disk
			FlatFileDAOUtils.copyDirectory(SAMPLE_DOMAIN_DIR, SAMPLE_DOMAIN_COPY_DIR, 3);
			LOGGER.info("Added sample domain: '{}'", SAMPLE_DOMAIN_ID);
		}

		// For SSL debugging
		// System.setProperty("javax.net.debug", "all");

		/*
		 * WORKAROUND to avoid accessExternalSchema error restricting protocols such as http://... in xacml schema: schemaLocation="http://www.w3.org/2001/xml.xsd"
		 */
		System.setProperty("javax.xml.accessExternalSchema", "all");

		// Create Jetty Server
		// set 'catalina.base' for Tomcat work dir and property in server's logback.xml
		System.setProperty("catalina.base", new File("target/server").getAbsolutePath());

		// Initialize embedded Tomcat server
		final Tomcat embeddedServer = new Tomcat();
		/*
		 * Increment server port after getting current value, to prepare for next server tests and avoid conflict with this one
		 */
		embeddedServer.setPort(port < 0 ? EMBEDDED_SERVER_PORT.incrementAndGet() : port);
		// enable JNDI
		embeddedServer.enableNaming();
		final Context webappCtx = embeddedServer.addWebapp(EMBEDDED_APP_CONTEXT_PATH, new File("src/main/webapp").getAbsolutePath());
		for (final LifecycleListener listener : webappCtx.findLifecycleListeners())
		{
			if (listener instanceof Tomcat.DefaultWebXmlListener)
			{
				webappCtx.removeLifecycleListener(listener);
			}
		}

		// Initialize server JNDI properties for embedded Tomcat
		final NamingResources webappNamingResources = webappCtx.getNamingResources();

		/*
		 * Override spring active profile context parameter with system property (no way to override context parameter with Tomcat embedded API, otherwise error
		 * "Duplicate context initialization parameter") spring.profiles.active may be set either via servletConfig init param or servletContext init param or JNDI property
		 * java:comp/env/spring.profiles.active or system property
		 */
		ContextEnvironment springActiveProfileEnv = new ContextEnvironment();
		springActiveProfileEnv.setName("spring.profiles.active");
		springActiveProfileEnv.setType("java.lang.String");
		springActiveProfileEnv.setValue((enableFastInfoset ? "+" : "-") + "fastinfoset");
		springActiveProfileEnv.setOverride(false);
		webappNamingResources.addEnvironment(springActiveProfileEnv);

		// override env-entry for domains sync interval
		ContextEnvironment syncIntervalEnv = new ContextEnvironment();
		syncIntervalEnv.setName("org.ow2.authzforce.domains.sync.interval");
		syncIntervalEnv.setType("java.lang.Integer");
		syncIntervalEnv.setValue(Integer.toString(domainSyncIntervalSec));
		syncIntervalEnv.setOverride(false);
		webappNamingResources.addEnvironment(syncIntervalEnv);

		// Override Anti-XML-DOS properties
		ContextEnvironment staxMaxChildElementsEnv = new ContextEnvironment();
		staxMaxChildElementsEnv.setName("org.apache.cxf.stax.maxChildElements");
		staxMaxChildElementsEnv.setType("java.lang.Integer");
		staxMaxChildElementsEnv.setValue(Integer.toString(XML_MAX_CHILD_ELEMENTS));
		staxMaxChildElementsEnv.setOverride(false);
		webappNamingResources.addEnvironment(staxMaxChildElementsEnv);

		ContextEnvironment staxMaxElementDepthEnv = new ContextEnvironment();
		staxMaxElementDepthEnv.setName("org.apache.cxf.stax.maxElementDepth");
		staxMaxElementDepthEnv.setType("java.lang.Integer");
		staxMaxElementDepthEnv.setValue(Integer.toString(XML_MAX_ELEMENT_DEPTH));
		staxMaxElementDepthEnv.setOverride(false);
		webappNamingResources.addEnvironment(staxMaxElementDepthEnv);

		if (!enableFastInfoset)
		{
			ContextEnvironment staxMaxAttCountEnv = new ContextEnvironment();
			staxMaxAttCountEnv.setName("org.apache.cxf.stax.maxAttributeCount");
			staxMaxAttCountEnv.setType("java.lang.Integer");
			staxMaxAttCountEnv.setValue(Integer.toString(XML_MAX_ATTRIBUTE_COUNT));
			staxMaxAttCountEnv.setOverride(false);
			webappNamingResources.addEnvironment(staxMaxAttCountEnv);

			ContextEnvironment staxMaxAttSizeEnv = new ContextEnvironment();
			staxMaxAttSizeEnv.setName("org.apache.cxf.stax.maxAttributeSize");
			staxMaxAttSizeEnv.setType("java.lang.Integer");
			staxMaxAttSizeEnv.setValue(Integer.toString(XML_MAX_ATTRIBUTE_SIZE));
			staxMaxAttSizeEnv.setOverride(false);
			webappNamingResources.addEnvironment(staxMaxAttSizeEnv);

			ContextEnvironment staxMaxTextLengthEnv = new ContextEnvironment();
			staxMaxTextLengthEnv.setName("org.apache.cxf.stax.maxTextLength");
			staxMaxTextLengthEnv.setType("java.lang.Integer");
			staxMaxTextLengthEnv.setValue(Integer.toString(XML_MAX_TEXT_LENGTH));
			staxMaxTextLengthEnv.setOverride(false);
			webappNamingResources.addEnvironment(staxMaxTextLengthEnv);
		}

		/*
		 * Example using JNDI property
		 */
		// System.setProperty("spring.profiles.active", );

		embeddedServer.start();

		return embeddedServer;
	}

	protected void startServerAndInitCLient(String remoteAppBaseUrl, boolean enableFastInfoset, int domainSyncIntervalSec, boolean enableJsonFormat) throws Exception
	{
		/*
		 * If embedded server not started and remoteAppBaseUrl null/empty (i.e. server/app to be started locally (embedded))
		 */
		if (!IS_EMBEDDED_SERVER_STARTED.get() && (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty()))
		{
			// Not a remote server -> start the embedded server (local)
			embeddedServer = startServer(-1, enableFastInfoset, domainSyncIntervalSec, false);
			IS_EMBEDDED_SERVER_STARTED.set(true);
		}

		/**
		 * Create the REST (JAX-RS) client
		 */
		// initialize client properties from Spring
		/*
		 * Workaround for: http://stackoverflow.com/questions/10184602/accessing -spring-context-in-testngs -beforetest https://jira.spring.io/browse/SPR-4072 https://jira.spring.io/browse/SPR-5404
		 * (duplicate of previous issue) springTestContextPrepareTestInstance() happens in
		 * 
		 * @BeforeClass before no access to Autowired beans by default in
		 * 
		 * @BeforeTest
		 */
		super.springTestContextPrepareTestInstance();
		/*
		 * WARNING: if tests are to be multi-threaded, modify according to Thread-safety section of CXF JAX-RS client API documentation http://cxf
		 * .apache.org/docs/jax-rs-client-api.html#JAX-RSClientAPI-ThreadSafety
		 */
		final String serverBaseAddress;
		/*
		 * Test if server is running embedded or not, i.e. remoteAppBaseUrl null/empty -> server embedded.
		 * 
		 * NB: member 'embeddedServer' may be null for this instance if embedded server was started by another class in the same Test; so embeddedServer == null does not mean that no embedded server
		 * is started
		 */
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			/*
			 * Server is a local/embedded one
			 */
			serverBaseAddress = "http://127.0.0.1:" + EMBEDDED_SERVER_PORT.get() + EMBEDDED_APP_CONTEXT_PATH;
		} else
		{
			serverBaseAddress = remoteAppBaseUrl;
		}

		final ClientConfiguration proxyClientConf;
		if (enableFastInfoset)
		{
			/*
			 * Use FASTINFOSET-aware client if FastInfoset enabled. More info on testing FastInfoSet with CXF: https://github.
			 * com/apache/cxf/blob/a0f0667ad6ef136ed32707d361732617bc152c2e/systests/jaxrs/src/test/java/org/apache /cxf/systest/jaxrs/JAXRSSoapBookTest.java.
			 */
			domainsAPIProxyClient = JAXRSClientFactory.create(serverBaseAddress, DomainsResourceFastInfoset.class, Collections.singletonList(clientJaxbProviderFI));
			proxyClientConf = WebClient.getConfig(domainsAPIProxyClient);
			/*
			 * WARNING: XmlMediaTypeHeaderSetter forces Content-type header to be "application/fastinfoset"; if not (with CXF 3.1.0), the first mediatype declared in WADL, i.e. Consume annotation of
			 * the service class ("application/xml") is set as Content-type, which causes exception on server-side such as: com.ctc.wstx.exc.WstxIOException: Invalid UTF-8 middle byte 0x0 (at char #0,
			 * byte #-1)
			 */
			proxyClientConf.getOutInterceptors().add(new XmlMediaTypeHeaderSetter(true));
			checkFiInterceptors(proxyClientConf);
		} else
		{
			if(enableJsonFormat) {	
				List<Object> providers = new ArrayList<Object>();
			    providers.add( clientJsonJaxbProvider );
				domainsAPIProxyClient = JAXRSClientFactory.create(serverBaseAddress, DomainsResource.class, providers);
				proxyClientConf = WebClient.getConfig(domainsAPIProxyClient);
				proxyClientConf.getHttpConduit().getClient().setAccept("application/json");
				
			} else {				
				domainsAPIProxyClient = JAXRSClientFactory.create(serverBaseAddress, DomainsResource.class, Collections.singletonList(clientJaxbProviderFI));			
				proxyClientConf = WebClient.getConfig(domainsAPIProxyClient);
				/*
				 * WARNING: XmlMediaTypeHeaderSetter forces Accept header to be "application/xml" only; else if Accept "application/fastinfoset" sent as well, the server returns fastinfoset which causes
				 * error on this client-side since not supported
				 */			
				proxyClientConf.getOutInterceptors().add(new XmlMediaTypeHeaderSetter(false));
			}
		}
		
		

		/**
		 * Request/response logging (for debugging).
		 */
		// if (LOGGER.isDebugEnabled()) {
		proxyClientConf.getInInterceptors().add(new LoggingInInterceptor());
		proxyClientConf.getOutInterceptors().add(new LoggingOutInterceptor());
		proxyClientConf.getHttpConduit().getClient().setConnectionTimeout(0);
		proxyClientConf.getHttpConduit().getClient().setReceiveTimeout(0);
		// }

		// Unmarshaller
		final Schema apiSchema = this.clientApiSchemaHandler.getSchema();

		unmarshaller = JAXB_CTX.createUnmarshaller();
		unmarshaller.setSchema(apiSchema);
	}

	protected void shutdownServer() throws Exception
	{
		// check whether server actually running
		if (embeddedServer != null && IS_EMBEDDED_SERVER_STARTED.get())
		{
			// Some tests may call tomcat.destroy(), some tests may just call
			// tomcat.stop(), some not call either method. Make sure that stop()
			// & destroy() are called as necessary.
			if (embeddedServer.getServer() != null && embeddedServer.getServer().getState() != LifecycleState.DESTROYED)
			{
				if (embeddedServer.getServer().getState() != LifecycleState.STOPPED)
				{
					embeddedServer.stop();
				}
				embeddedServer.destroy();
			}

			IS_EMBEDDED_SERVER_STARTED.set(false);
		}
	}

	public static void main(String... args) throws IllegalArgumentException, ServletException, IOException, LifecycleException
	{
		final int port;
		final boolean enableFastInfoset;
		final int domainSyncIntervalSec;
		if (args.length == 0)
		{
			port = 8080;
			enableFastInfoset = false;
			domainSyncIntervalSec = -1;
		} else if (args.length < 2)
		{
			System.out.println("Usage: java RestServiceTest [port enableFastInfoset domainsSyncIntervalSec]");
			System.out.println("- port: (integer) server port, dynamically allocated if negative. Default: 8080.");
			System.out.println("- enableFastInfoset: (true|false) whether to enable FastInfoset support (true) or not (false). Default: false.");
			System.out.println("- domainsSyncIntervalSec: (integer) domains sync interval (seconds), disabled if negative. Default: -1");
			throw new IllegalArgumentException("Invalid args. Expected args: enableFastInfoset domainsSyncIntervalSec");
		} else
		{
			port = Integer.parseInt(args[0], 10);
			enableFastInfoset = Boolean.valueOf(args[1]);
			domainSyncIntervalSec = Integer.parseInt(args[2], 10);
		}

		final Tomcat tomcat = startServer(port, enableFastInfoset, domainSyncIntervalSec, true);
		System.out.println("Server up and listening!");
		tomcat.getServer().await();
	}

	private static void checkFiInterceptors(ClientConfiguration cfg)
	{
		int count = 0;
		for (Interceptor<?> in : cfg.getInInterceptors())
		{
			if (in instanceof FIStaxInInterceptor)
			{
				count++;
				break;
			}
		}
		for (Interceptor<?> in : cfg.getOutInterceptors())
		{
			if (in instanceof FIStaxOutInterceptor)
			{
				count++;
				break;
			}
		}
		if (count != 2)
		{
			throw new RuntimeException("In and Out FastInfoset interceptors are expected");
		}
	}

}
