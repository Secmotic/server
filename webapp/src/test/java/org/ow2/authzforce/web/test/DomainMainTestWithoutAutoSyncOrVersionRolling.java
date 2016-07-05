/**
 * Copyright (C) 2015-2015 Thales Services SAS. All rights reserved. No warranty, explicit or implicit, provided.
 */
package org.ow2.authzforce.web.test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.ClientErrorException;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response.Status;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;

import oasis.names.tc.xacml._3_0.core.schema.wd_17.IdReferenceType;
import oasis.names.tc.xacml._3_0.core.schema.wd_17.PolicySet;
import oasis.names.tc.xacml._3_0.core.schema.wd_17.Request;
import oasis.names.tc.xacml._3_0.core.schema.wd_17.Response;

import org.apache.cxf.interceptor.LoggingInInterceptor;
import org.apache.cxf.interceptor.LoggingOutInterceptor;
import org.apache.cxf.jaxrs.client.ClientConfiguration;
import org.apache.cxf.jaxrs.client.WebClient;
import org.ow2.authzforce.core.pdp.impl.DefaultRequestFilter;
import org.ow2.authzforce.core.pdp.impl.MultiDecisionRequestFilter;
import org.ow2.authzforce.core.test.custom.TestCombinedDecisionResultFilter;
import org.ow2.authzforce.core.test.custom.TestDNSNameValueEqualFunction;
import org.ow2.authzforce.core.test.custom.TestDNSNameWithPortValue;
import org.ow2.authzforce.core.test.custom.TestOnPermitApplySecondCombiningAlg;
import org.ow2.authzforce.core.test.utils.TestUtils;
import org.ow2.authzforce.core.xmlns.test.TestAttributeProvider;
import org.ow2.authzforce.pap.dao.flatfile.FlatFileBasedDomainsDAO;
import org.ow2.authzforce.pap.dao.flatfile.FlatFileBasedDomainsDAO.PdpCoreFeature;
import org.ow2.authzforce.pap.dao.flatfile.FlatFileBasedDomainsDAO.PdpFeatureType;
import org.ow2.authzforce.pap.dao.flatfile.FlatFileDAOUtils;
import org.ow2.authzforce.rest.api.jaxrs.AttributeProvidersResource;
import org.ow2.authzforce.rest.api.jaxrs.DomainPropertiesResource;
import org.ow2.authzforce.rest.api.jaxrs.DomainResource;
import org.ow2.authzforce.rest.api.jaxrs.PdpPropertiesResource;
import org.ow2.authzforce.rest.api.jaxrs.PoliciesResource;
import org.ow2.authzforce.rest.api.jaxrs.PolicyResource;
import org.ow2.authzforce.rest.api.jaxrs.PolicyVersionResource;
import org.ow2.authzforce.rest.api.xmlns.AttributeProviders;
import org.ow2.authzforce.rest.api.xmlns.Domain;
import org.ow2.authzforce.rest.api.xmlns.DomainProperties;
import org.ow2.authzforce.rest.api.xmlns.Feature;
import org.ow2.authzforce.rest.api.xmlns.PdpProperties;
import org.ow2.authzforce.rest.api.xmlns.PdpPropertiesUpdate;
import org.ow2.authzforce.rest.api.xmlns.ResourceContent;
import org.ow2.authzforce.rest.api.xmlns.Resources;
import org.ow2.authzforce.xmlns.pdp.ext.AbstractAttributeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;
import org.w3._2005.atom.Link;

/**
 * Main tests specific to a domain, requires {@link DomainSetTest} to be run first
 */
public class DomainMainTestWithoutAutoSyncOrVersionRolling extends RestServiceTest
{
	private static final Logger LOGGER = LoggerFactory.getLogger(DomainMainTestWithoutAutoSyncOrVersionRolling.class);

	private static final FileFilter DIRECTORY_FILTER = new FileFilter()
	{

		@Override
		public boolean accept(File pathname)
		{
			return pathname.isDirectory();
		}

	};

	private WebClient httpHeadClient;

	private DomainAPIHelper testDomainHelper = null;

	private DomainResource testDomain = null;
	private String testDomainId = null;
	private File testDomainPropertiesFile;

	private String testDomainExternalId = "test";

	/**
	 * Test parameters from testng.xml are ignored when executing with maven surefire plugin, so we use default values for all.
	 * 
	 * WARNING: the BeforeTest-annotated method must be in the test class, not in a super class although the same method logic is used in other test class
	 * 
	 * @param remoteAppBaseUrl
	 * @param enableFastInfoset
	 * @param domainSyncIntervalSec
	 * @param enableJsonFormat
	 * @throws Exception
	 */
	@Parameters({ "remote.base.url", "enableFastInfoset", "org.ow2.authzforce.domains.sync.interval", "enableJsonFormat" })
	@BeforeTest()
	public void beforeTest(@Optional String remoteAppBaseUrl, @Optional("false") boolean enableFastInfoset, @Optional("-1") int domainSyncIntervalSec, @Optional("false") Boolean enableJsonFormat) throws Exception
	{
		startServerAndInitCLient(remoteAppBaseUrl, enableFastInfoset, domainSyncIntervalSec, enableJsonFormat);
	}

	/**
	 * 
	 * WARNING: the AfterTest-annotated method must be in the test class, not in a super class although the same method logic is used in other test class
	 *
	 * @throws Exception
	 */
	@AfterTest
	public void afterTest() throws Exception
	{
		shutdownServer();
	}

	/**
	 * @param remoteAppBaseUrl
	 * @param enableFastInfoset
	 * @param enableJsonFormat
	 * @throws Exception
	 * 
	 *             NB: use Boolean class instead of boolean primitive type for Testng parameter, else the default value in @Optional annotation is not handled properly.
	 */
	@Parameters({ "remote.base.url", "enableFastInfoset", "enableJsonFormat" })
	@BeforeClass
	public void addDomain(@Optional String remoteAppBaseUrl, @Optional("false") Boolean enableFastInfoset, @Optional("false") Boolean enableJsonFormat) throws Exception
	{
		final Link domainLink = domainsAPIProxyClient.addDomain(new DomainProperties("Some description", testDomainExternalId));
		assertNotNull(domainLink, "Domain creation failure");

		// The link href gives the new domain ID
		testDomainId = domainLink.getHref();
		LOGGER.debug("Added domain ID={}", testDomainId);
		testDomain = domainsAPIProxyClient.getDomainResource(testDomainId);
		assertNotNull(testDomain, String.format("Error retrieving domain ID=%s", testDomainId));
		File testDomainDir = new File(RestServiceTest.DOMAINS_DIR, testDomainId);
		testDomainPropertiesFile = new File(testDomainDir, RestServiceTest.DOMAIN_PROPERTIES_FILENAME);
		this.testDomainHelper = new DomainAPIHelper(testDomainId, testDomain, unmarshaller, pdpModelHandler);

		final ClientConfiguration apiProxyClientConf = WebClient.getConfig(domainsAPIProxyClient);
		final String appBaseUrl = apiProxyClientConf.getEndpoint().getEndpointInfo().getAddress();
		httpHeadClient = WebClient.create(appBaseUrl, true);
		if (LOGGER.isDebugEnabled())
		{
			final ClientConfiguration builderConf = WebClient.getConfig(httpHeadClient);
			builderConf.getInInterceptors().add(new LoggingInInterceptor());
			builderConf.getOutInterceptors().add(new LoggingOutInterceptor());
		}

		assertNotNull(testDomain, String.format("Error retrieving domain ID=%s", testDomainId));
	}

	@AfterClass
	/**
	 * deleteDomain() already tested in {@link DomainSetTest#deleteDomains()}, so this is just for cleaning after testing
	 */
	public void deleteDomain() throws Exception
	{
		assertNotNull(testDomain.deleteDomain(), String.format("Error deleting domain ID=%s", testDomainId));
	}

	@Test
	public void getDomain()
	{
		final Domain domainResourceInfo = testDomain.getDomain();
		assertNotNull(domainResourceInfo);

		final DomainProperties props = domainResourceInfo.getProperties();
		assertNotNull(props);
	}

	@Test
	public void getDomainProperties()
	{
		final DomainPropertiesResource propsResource = testDomain.getDomainPropertiesResource();
		assertNotNull(propsResource);

		final DomainProperties props = propsResource.getDomainProperties();
		assertNotNull(props);
	}

	@Test(dependsOnMethods = { "getDomainProperties" })
	public void updateDomainProperties()
	{
		final DomainPropertiesResource propsResource = testDomain.getDomainPropertiesResource();
		assertNotNull(propsResource);

		byte[] randomBytes = new byte[128];
		RestServiceTest.PRNG.nextBytes(randomBytes);
		String description = FlatFileDAOUtils.base64UrlEncode(randomBytes);
		byte[] randomBytes2 = new byte[16];
		RestServiceTest.PRNG.nextBytes(randomBytes2);
		// externalId must be a xs:NCName, therefore cannot start with a number
		String newExternalId = "external" + FlatFileDAOUtils.base64UrlEncode(randomBytes2);
		propsResource.updateDomainProperties(new DomainProperties(description, newExternalId));
		// verify result
		final DomainProperties newProps = testDomain.getDomainPropertiesResource().getDomainProperties();
		assertEquals(newProps.getDescription(), description);
		assertEquals(newProps.getExternalId(), newExternalId);

		// test old externalID -> should fail
		final List<Link> domainLinks = domainsAPIProxyClient.getDomains(testDomainExternalId).getLinks();
		assertTrue(domainLinks.isEmpty(), "Update of externalId on GET /domains/" + this.testDomainId + "/properties failed: old externaldId still mapped to the domain");

		testDomainExternalId = newExternalId;

		// test the new externalId
		final List<Link> domainLinks2 = domainsAPIProxyClient.getDomains(newExternalId).getLinks();
		String matchedDomainId = domainLinks2.get(0).getHref();
		assertEquals(matchedDomainId, testDomainId, "Update of externalId on GET /domains/" + this.testDomainId + "/properties failed: getDomains(externalId = " + newExternalId
				+ ") returned wrong domainId: " + matchedDomainId + " instead of " + testDomainId);
	}

	private void verifySyncAfterDomainPropertiesFileModification(String newExternalId)
	{
		// test the old externalId
		final List<Link> domainLinks = domainsAPIProxyClient.getDomains(testDomainExternalId).getLinks();
		assertTrue(domainLinks.isEmpty(), "Manual sync of externalId with GET or HEAD /domains/" + this.testDomainId + "/properties failed: old externaldId still mapped to the domain:");

		testDomainExternalId = newExternalId;

		// test the new externalId
		// test externalId
		final List<Link> domainLinks2 = domainsAPIProxyClient.getDomains(newExternalId).getLinks();
		final String matchedDomainId = domainLinks2.get(0).getHref();
		assertEquals(matchedDomainId, testDomainId, "Manual sync of externalId with GET or HEAD /domains/" + this.testDomainId + "/properties failed: getDomains(externalId = " + newExternalId
				+ ") returned wrong domainId: " + matchedDomainId + " instead of " + testDomainId);
	}

	@Parameters({ "remote.base.url", "legacy.fs" })
	@Test(dependsOnMethods = { "getDomainProperties" })
	public void headDomainPropertiesAfterFileModification(@Optional String remoteAppBaseUrl, @Optional("false") Boolean isFilesystemLegacy) throws InterruptedException, JAXBException
	{

		// skip test if server not started locally
		if (remoteAppBaseUrl != null && !remoteAppBaseUrl.isEmpty())
		{
			return;
		}

		final String newExternalId = testDomainExternalId + "ter";
		testDomainHelper.modifyDomainPropertiesFile(newExternalId, isFilesystemLegacy);

		// manual sync with HEAD /domains/{id}/properties
		final javax.ws.rs.core.Response response = httpHeadClient.reset().path("domains").path(testDomainId).path("properties").accept(MediaType.APPLICATION_XML).head();
		assertEquals(response.getStatus(), javax.ws.rs.core.Response.Status.OK.getStatusCode(), "HEAD /domains/" + testDomainId + "/properties failed");

		verifySyncAfterDomainPropertiesFileModification(newExternalId);

	}

	@Parameters({ "remote.base.url", "legacy.fs" })
	@Test(dependsOnMethods = { "getDomainProperties" })
	public void getDomainPropertiesAfterFileModification(@Optional String remoteAppBaseUrl, @Optional("false") Boolean isFilesystemLegacy) throws JAXBException, InterruptedException
	{
		// skip test if server not started locally
		if (remoteAppBaseUrl != null && !remoteAppBaseUrl.isEmpty())
		{
			return;
		}

		final String newExternalId = testDomainExternalId + "bis";
		testDomainHelper.modifyDomainPropertiesFile(newExternalId, isFilesystemLegacy);

		// manual sync with GET /domains/{id}/properties
		final DomainProperties newPropsFromAPI = testDomain.getDomainPropertiesResource().getDomainProperties();
		final String externalIdFromAPI = newPropsFromAPI.getExternalId();
		assertEquals(externalIdFromAPI, newExternalId, "Manual sync of externalId with GET /domains/" + this.testDomainId + "/properties failed: externaldId returned (" + externalIdFromAPI
				+ ") does not match externalId in modified file: " + testDomainPropertiesFile);

		verifySyncAfterDomainPropertiesFileModification(newExternalId);
	}

	@Test(dependsOnMethods = { "getDomain" })
	public void getPap()
	{
		final ResourceContent papResource = testDomain.getPapResource().getPAP();
		assertNotNull(papResource);
	}

	@Test(dependsOnMethods = { "getPap" })
	public void getAttributeProviders()
	{
		final AttributeProviders attributeProviders = testDomain.getPapResource().getAttributeProvidersResource().getAttributeProviderList();
		assertNotNull(attributeProviders);
	}

	@Test(dependsOnMethods = { "getAttributeProviders" })
	public void updateAttributeProviders() throws JAXBException
	{
		final AttributeProvidersResource attributeProvidersResource = testDomain.getPapResource().getAttributeProvidersResource();
		JAXBElement<TestAttributeProvider> jaxbElt = testDomainHelper.unmarshal(new File(RestServiceTest.XACML_SAMPLES_DIR, "pdp/IIA002(PolicySet)/attributeProvider.xml"), TestAttributeProvider.class);
		TestAttributeProvider testAttrProvider = jaxbElt.getValue();
		AttributeProviders updateAttrProvidersResult = attributeProvidersResource.updateAttributeProviderList(new AttributeProviders(Collections.<AbstractAttributeProvider> singletonList(testAttrProvider)));
		assertNotNull(updateAttrProvidersResult);
		assertEquals(updateAttrProvidersResult.getAttributeProviders().size(), 1);
		AbstractAttributeProvider updateAttrProvidersItem = updateAttrProvidersResult.getAttributeProviders().get(0);
		if (updateAttrProvidersItem instanceof TestAttributeProvider)
		{
			assertEquals(((TestAttributeProvider) updateAttrProvidersItem).getAttributes(), testAttrProvider.getAttributes());
		} else
		{
			fail("AttributeProvider in result of updateAttributeProviderList(inputAttributeProviders) does not match the one in inputAttributeProviders: " + updateAttrProvidersItem);
		}

		// check getAttributeProviders
		AttributeProviders getAttrProvidersResult = attributeProvidersResource.getAttributeProviderList();
		assertNotNull(getAttrProvidersResult);
		assertEquals(getAttrProvidersResult.getAttributeProviders().size(), 1);
		AbstractAttributeProvider getAttrProvidersItem = getAttrProvidersResult.getAttributeProviders().get(0);
		if (getAttrProvidersItem instanceof TestAttributeProvider)
		{
			assertEquals(((TestAttributeProvider) getAttrProvidersItem).getAttributes(), testAttrProvider.getAttributes());
		} else
		{
			fail("AttributeProvider in result of updateAttributeProviderList(inputAttributeProviders) does not match the one in inputAttributeProviders: " + getAttrProvidersItem);
		}

	}

	@Test(dependsOnMethods = { "getPap" })
	public void getPolicies()
	{
		final Resources resources = testDomain.getPapResource().getPoliciesResource().getPolicies();
		assertNotNull(resources);
		assertTrue(resources.getLinks().size() > 0, "No resource for root policy found");
	}
	
	@Test(dependsOnMethods = { "getDomainProperties", "getPolicies" })
	public void getRootPolicy()
	{
		final IdReferenceType rootPolicyRef = testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties().getApplicablePolicies().getRootPolicyRef();
		final List<Link> links = testDomain.getPapResource().getPoliciesResource().getPolicyResource(rootPolicyRef.getValue()).getPolicyVersions().getLinks();
		assertTrue(links.size() > 0, "No root policy version found");
	}

	private static final String TEST_POLICY_ID0 = "testPolicyAdd";

	@Test(dependsOnMethods = { "getRootPolicy" })
	public void addAndGetPolicy()
	{
		PolicySet policySet = RestServiceTest.createDumbPolicySet(TEST_POLICY_ID0, "1.0");
		testDomainHelper.testAddAndGetPolicy(policySet);

		PolicySet policySet2 = RestServiceTest.createDumbPolicySet(TEST_POLICY_ID0, "1.1");
		testDomainHelper.testAddAndGetPolicy(policySet2);

		PolicySet policySet3 = RestServiceTest.createDumbPolicySet(TEST_POLICY_ID0, "1.2");
		testDomainHelper.testAddAndGetPolicy(policySet3);
		Resources policyVersionsResources = testDomain.getPapResource().getPoliciesResource().getPolicyResource(TEST_POLICY_ID0).getPolicyVersions();
		assertEquals(policyVersionsResources.getLinks().size(), 3);

	}

	private static final String TEST_POLICY_ID1 = "policyToTestGetVersions";

	static boolean isHrefMatched(String href, List<Link> links)
	{
		for (Link link : links)
		{
			if (link.getHref().equals(href))
			{
				return true;
			}
		}

		return false;
	}

	@Test(dependsOnMethods = { "addAndGetPolicy" })
	public void getPolicyVersions()
	{
		String[] policyVersions = { "1.0", "1.1", "1.2" };
		for (String v : policyVersions)
		{
			PolicySet policySet = RestServiceTest.createDumbPolicySet(TEST_POLICY_ID1, v);
			testDomainHelper.testAddAndGetPolicy(policySet);
		}

		PoliciesResource policiesRes = testDomain.getPapResource().getPoliciesResource();
		PolicyResource policyRes = policiesRes.getPolicyResource(TEST_POLICY_ID1);
		List<Link> versionLinks = policyRes.getPolicyVersions().getLinks();
		assertEquals(policyVersions.length, versionLinks.size(), "Invalid number of versions returned by getPolicyVersions()");
		for (String v : policyVersions)
		{
			assertTrue(isHrefMatched(v, versionLinks), "Version missing in list returned by API getPolicyVersions()");
		}
	}

	private static final String TEST_POLICY_ID2 = "policyToTestGetLatestV";

	@Test(dependsOnMethods = { "getPolicyVersions" })
	public void getLatestPolicyVersion()
	{
		PolicySet policySet = RestServiceTest.createDumbPolicySet(TEST_POLICY_ID2, "1.0");
		testDomainHelper.testAddAndGetPolicy(policySet);

		PolicySet policySet2 = RestServiceTest.createDumbPolicySet(TEST_POLICY_ID2, "1.1");
		testDomainHelper.testAddAndGetPolicy(policySet2);

		PolicySet policySet3 = RestServiceTest.createDumbPolicySet(TEST_POLICY_ID2, "1.2");
		testDomainHelper.testAddAndGetPolicy(policySet3);

		PoliciesResource policiesRes = testDomain.getPapResource().getPoliciesResource();
		PolicyResource policyRes = policiesRes.getPolicyResource(TEST_POLICY_ID2);
		final PolicySet getRespPolicySet = policyRes.getPolicyVersionResource("latest").getPolicyVersion();
		DomainAPIHelper.matchPolicySets(getRespPolicySet, policySet3, "getLatestPolicyVersion");

	}

	@Test(dependsOnMethods = { "addAndGetPolicy" })
	public void setValidMaxPolicyCount() throws JAXBException
	{
		testDomainHelper.resetPdpAndPrp();

		final int maxPolicyCount = 3;
		int actualMax = testDomainHelper.updateAndGetMaxPolicyCount(maxPolicyCount);
		assertEquals(maxPolicyCount, actualMax, "Max policy count set with PUT does not match the one retrieve with GET");
		// reset maxpolicycount to undefined for other tests
		testDomainHelper.updateMaxPolicyCount(-1);
	}

	@Test(dependsOnMethods = { "setValidMaxPolicyCount" }, expectedExceptions = { BadRequestException.class })
	public void setTooSmallMaxPolicyCount() throws JAXBException
	{
		testDomainHelper.resetPdpAndPrp();

		final int maxPolicyCount = 3;
		testDomainHelper.updateMaxPolicyCount(maxPolicyCount);

		for (int i = 0; i < maxPolicyCount - 1; i++)
		{
			PolicySet policySet = RestServiceTest.createDumbPolicySet("policy_setTooSmallMaxPolicyCount" + i, "1.0");
			testDomainHelper.testAddAndGetPolicy(policySet);
		}

		// try setting max policy count lower than current number of policies
		testDomainHelper.updateMaxPolicyCount(maxPolicyCount - 1);
	}

	@Test(dependsOnMethods = { "addAndGetPolicy" }, expectedExceptions = { ForbiddenException.class })
	public void addTooManyPolicies() throws JAXBException
	{
		// replace all policies with one root policy and this is the only one policy
		testDomainHelper.resetPdpAndPrp();

		final int maxPolicyCountPerDomain = 3;
		testDomainHelper.updateMaxPolicyCount(maxPolicyCountPerDomain);

		// So we can only add maxPolicyCountPerDomain-1 more policies before reaching the max
		for (int i = 0; i < maxPolicyCountPerDomain - 1; i++)
		{
			PolicySet policySet = RestServiceTest.createDumbPolicySet("policyTooMany" + i, "1.0");
			testDomainHelper.testAddAndGetPolicy(policySet);
		}

		// verify that all policies are there
		List<Link> links = testDomain.getPapResource().getPoliciesResource().getPolicies().getLinks();
		Set<Link> policyLinkSet = new HashSet<>(links);
		assertEquals(links.size(), policyLinkSet.size(), "Duplicate policies returned in links from getPolicies: " + links);

		assertEquals(policyLinkSet.size(), maxPolicyCountPerDomain, "policies removed before reaching value of property 'org.ow2.authzforce.domain.maxPolicyCount'. Actual versions: " + links);

		// We should have reached the max, so adding one more should be rejected by the server
		PolicySet policySet = RestServiceTest.createDumbPolicySet("policyTooMany", "1.0");
		// this should raise ForbiddenException
		testDomainHelper.testAddAndGetPolicy(policySet);
		fail("Failed to enforce maxPoliciesPerDomain property: " + maxPolicyCountPerDomain);
	}

	private static final String TEST_POLICY_ID = "policyTooManyV";

	@Test(dependsOnMethods = { "setValidMaxPolicyCount" })
	public void disablePolicyVersionRolling()
	{
		final boolean isEnabled = testDomainHelper.setPolicyVersionRollingAndGetStatus(false);
		assertFalse(isEnabled, "Failed to disable policy version rolling");
	}

	@Test(dependsOnMethods = { "disablePolicyVersionRolling" })
	public void setValidMaxPolicyVersionCount() throws JAXBException
	{
		testDomainHelper.resetPdpAndPrp();

		final int maxPolicyVersionCount = 3;
		int actualMax = testDomainHelper.updateAndGetMaxPolicyVersionCount(maxPolicyVersionCount);
		assertEquals(maxPolicyVersionCount, actualMax, "Max policy count set with PUT does not match the one retrieve with GET");
		// reset max version count for other tests
		testDomainHelper.updateVersioningProperties(-1, false);
	}

	@Test(dependsOnMethods = { "setValidMaxPolicyVersionCount" }, expectedExceptions = { BadRequestException.class })
	public void setTooSmallMaxPolicyVersionCount() throws JAXBException
	{
		testDomainHelper.resetPdpAndPrp();

		final int maxVersionCountPerPolicy = 3;
		testDomainHelper.updateVersioningProperties(maxVersionCountPerPolicy, false);

		for (int i = 0; i < maxVersionCountPerPolicy; i++)
		{
			PolicySet policySet = RestServiceTest.createDumbPolicySet(TEST_POLICY_ID, "1." + i);
			testDomainHelper.testAddAndGetPolicy(policySet);
		}

		// try setting max policy count lower than current number of policies
		testDomainHelper.updateVersioningProperties(maxVersionCountPerPolicy - 1, false);
	}

	@Test(dependsOnMethods = { "addTooManyPolicies" }, expectedExceptions = { ForbiddenException.class })
	public void addTooManyPolicyVersions() throws JAXBException
	{
		testDomainHelper.resetPdpAndPrp();

		final int maxVersionCountPerPolicy = 3;
		testDomainHelper.updateVersioningProperties(maxVersionCountPerPolicy, false);
		for (int i = 0; i < maxVersionCountPerPolicy; i++)
		{
			PolicySet policySet = RestServiceTest.createDumbPolicySet(TEST_POLICY_ID, "1." + i);
			testDomainHelper.testAddAndGetPolicy(policySet);
		}

		// verify that all versions are there
		List<Link> links = testDomain.getPapResource().getPoliciesResource().getPolicyResource(TEST_POLICY_ID).getPolicyVersions().getLinks();
		Set<Link> versionSet = new HashSet<>(links);
		assertEquals(links.size(), versionSet.size(), "Duplicate versions returned in links from getPolicyResource(policyId): " + links);

		assertEquals(versionSet.size(), maxVersionCountPerPolicy, "versions removed before reaching maxVersionCountPerPolicy'. Actual versions: " + links);

		PolicySet policySet = RestServiceTest.createDumbPolicySet(TEST_POLICY_ID, "2.0");
		// this should raise ForbiddenException
		testDomainHelper.testAddAndGetPolicy(policySet);
		fail("Failed to enforce property 'maxVersionCountPerPolicy': " + maxVersionCountPerPolicy);
	}

	@Test(dependsOnMethods = { "addAndGetPolicy" })
	public void addConflictingPolicyVersion()
	{
		PolicySet policySet = RestServiceTest.createDumbPolicySet("testAddConflictingPolicyVersion", "1.0");
		testDomainHelper.testAddAndGetPolicy(policySet);

		// must be rejected
		try
		{
			testDomainHelper.testAddAndGetPolicy(policySet);
			fail("Adding the same policy did not fail with HTTP 409 Conflict as expected");
		} catch (ClientErrorException e)
		{
			assertEquals(e.getResponse().getStatus(), Status.CONFLICT.getStatusCode());
		}
	}

	private static final String TEST_POLICY_DELETE_ID = "testPolicyDelete";

	@Test(dependsOnMethods = { "addAndGetPolicy" })
	public void deleteAndTryGetUnusedPolicy() throws JAXBException
	{
		testDomainHelper.resetPdpAndPrp();
		PolicySet policySet1 = RestServiceTest.createDumbPolicySet(TEST_POLICY_DELETE_ID, "1.2.3");
		testDomainHelper.testAddAndGetPolicy(policySet1);

		PolicySet policySet2 = RestServiceTest.createDumbPolicySet(TEST_POLICY_DELETE_ID, "1.3.1");
		testDomainHelper.testAddAndGetPolicy(policySet2);

		// delete policy (all versions)
		PolicyResource policyResource = testDomain.getPapResource().getPoliciesResource().getPolicyResource(TEST_POLICY_DELETE_ID);
		Resources versionsResources = policyResource.deletePolicy();
		assertNotNull(versionsResources);
		assertTrue(DomainAPIHelper.isHrefMatched("1.2.3", versionsResources.getLinks()));
		assertTrue(DomainAPIHelper.isHrefMatched("1.3.1", versionsResources.getLinks()));

		try
		{
			policyResource.getPolicyVersions();
			fail("Policy (all versions) removal failed (resource still there");
		} catch (NotFoundException e)
		{
			// OK
		}

		PoliciesResource policiesRes = testDomain.getPapResource().getPoliciesResource();
		assertFalse(DomainAPIHelper.isHrefMatched(TEST_POLICY_DELETE_ID, policiesRes.getPolicies().getLinks()),
				"Deleted policy resource (all versions) is still in links returned by getPoliciesResource()");
	}

	@Test(dependsOnMethods = { "deleteAndTryGetUnusedPolicy" })
	public void deleteAndTryGetPolicyVersion()
	{
		PolicySet policySet1 = RestServiceTest.createDumbPolicySet(TEST_POLICY_DELETE_ID, "1.2.3");
		testDomainHelper.testAddAndGetPolicy(policySet1);

		PolicySet policySet2 = RestServiceTest.createDumbPolicySet(TEST_POLICY_DELETE_ID, "1.3.1");
		testDomainHelper.testAddAndGetPolicy(policySet2);

		// delete one of the versions
		PolicyVersionResource policyVersionRes = testDomain.getPapResource().getPoliciesResource().getPolicyResource(TEST_POLICY_DELETE_ID).getPolicyVersionResource("1.2.3");
		PolicySet deletedPolicy = policyVersionRes.deletePolicyVersion();
		DomainAPIHelper.matchPolicySets(deletedPolicy, policySet1, "deleteAndTryGetPolicyVersion");

		try
		{
			policyVersionRes.getPolicyVersion();
			org.testng.Assert.fail("Policy version removal failed (resource still there");
		} catch (NotFoundException e)
		{
			// OK
		}

		Resources policyVersionsResources = testDomain.getPapResource().getPoliciesResource().getPolicyResource(TEST_POLICY_DELETE_ID).getPolicyVersions();
		assertEquals(policyVersionsResources.getLinks().size(), 1);
		assertTrue(DomainAPIHelper.isHrefMatched("1.3.1", policyVersionsResources.getLinks()));
	}

	private static final String TEST_POLICY_DELETE_SINGLE_VERSION_ID = "testPolicyDeleteSingleVersion";

	@Test(dependsOnMethods = { "deleteAndTryGetPolicyVersion" }, expectedExceptions = { ForbiddenException.class })
	public void deleteSingleVersionOfPolicy()
	{
		PolicySet policySet1 = RestServiceTest.createDumbPolicySet(TEST_POLICY_DELETE_SINGLE_VERSION_ID, "1.2.3");
		testDomainHelper.testAddAndGetPolicy(policySet1);

		PolicyResource policyRes = testDomain.getPapResource().getPoliciesResource().getPolicyResource(TEST_POLICY_DELETE_SINGLE_VERSION_ID);
		// delete this single version
		PolicyVersionResource policyVersionRes = policyRes.getPolicyVersionResource("1.2.3");
		// this should raise ForbiddenException, as this is the only remaining version (there must be at least one)
		policyVersionRes.deletePolicyVersion();
		org.testng.Assert.fail("Policy version removal succeeded although there was only one version for the policy");
	}

	@Test(dependsOnMethods = { "getRootPolicy", "deleteAndTryGetUnusedPolicy" })
	public void deleteRootPolicy()
	{
		final IdReferenceType rootPolicyRef = testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties().getApplicablePolicies().getRootPolicyRef();

		// must be rejected
		try
		{
			testDomain.getPapResource().getPoliciesResource().getPolicyResource(rootPolicyRef.getValue()).deletePolicy();
			fail("Wrongly accepted to remove root policy");
		} catch (BadRequestException e)
		{
			// OK, expected
		}

		assertNotNull(testDomain.getPapResource().getPoliciesResource().getPolicyResource(rootPolicyRef.getValue()).getPolicyVersions(),
				"Root policy was actually removed although server return HTTP 400 for removal attempt");

		// make sure rootPolicyRef unchanged
		assertEquals(rootPolicyRef, testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties().getApplicablePolicies().getRootPolicyRef(),
				"rootPolicyRef changed although root policy removal rejected");
	}

	@Test(dependsOnMethods = { "addAndGetPolicy", "deleteRootPolicy", "updateDomainProperties" })
	public void updateRootPolicyRefToValidPolicy() throws JAXBException
	{
		PdpPropertiesResource propsRes = testDomain.getPapResource().getPdpPropertiesResource();

		// get current root policy ID
		final IdReferenceType rootPolicyRef = propsRes.getOtherPdpProperties().getApplicablePolicies().getRootPolicyRef();
		PolicyResource oldRootPolicyRes = testDomain.getPapResource().getPoliciesResource().getPolicyResource(rootPolicyRef.getValue());

		// point rootPolicyRef to another one
		String oldRootPolicyId = rootPolicyRef.getValue();
		String newRootPolicyId = oldRootPolicyId + ".new";
		PolicySet policySet = RestServiceTest.createDumbPolicySet(newRootPolicyId, "1.0");
		IdReferenceType newRootPolicyRef = testDomainHelper.setRootPolicy(policySet, false);

		// verify update
		final PdpProperties newProps = testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties();
		assertEquals(newProps.getApplicablePolicies().getRootPolicyRef(), newRootPolicyRef,
				"Root PolicyRef returned by getOtherPdpProperties() does not match last set rootPolicyRef via updateOtherPdpProperties()");

		// delete previous root policy ref to see if it succeeds
		oldRootPolicyRes.deletePolicy();
	}

	@Test(dependsOnMethods = { "updateRootPolicyRefToValidPolicy" })
	public void updateRootPolicyRefToMissingPolicy() throws JAXBException
	{
		PdpPropertiesResource propsRes = testDomain.getPapResource().getPdpPropertiesResource();
		PdpProperties props = propsRes.getOtherPdpProperties();

		// get current root policy ID
		final IdReferenceType rootPolicyRef = props.getApplicablePolicies().getRootPolicyRef();

		// point rootPolicyRef to another one
		String oldRootPolicyId = rootPolicyRef.getValue();
		String newRootPolicyId = oldRootPolicyId + ".new";

		IdReferenceType newRootPolicyRef = new IdReferenceType(newRootPolicyId, null, null, null);

		// MUST fail
		try
		{
			propsRes.updateOtherPdpProperties(new PdpPropertiesUpdate(null, newRootPolicyRef));
			fail("Setting rootPolicyRef to missing policy did not fail as expected");
		} catch (BadRequestException e)
		{
			// OK
		}

		// make sure rootPolicyRef unchanged
		assertEquals(rootPolicyRef, testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties().getApplicablePolicies().getRootPolicyRef());
	}

	@Test(dependsOnMethods = { "updateRootPolicyRefToValidPolicy" })
	public void updateRootPolicyRefToValidVersion() throws JAXBException
	{
		testDomainHelper.updateMaxPolicyCount(-1);
		testDomainHelper.updateVersioningProperties(-1, false);

		PdpPropertiesResource propsRes = testDomain.getPapResource().getPdpPropertiesResource();

		PolicySet policySet = RestServiceTest.createDumbPolicySet("testUpdateRootPolicyRefToValidVersion", "4.4");
		testDomainHelper.testAddAndGetPolicy(policySet);

		PolicySet policySet2 = RestServiceTest.createDumbPolicySet("testUpdateRootPolicyRefToValidVersion", "4.5");
		testDomainHelper.testAddAndGetPolicy(policySet2);

		PolicySet policySet3 = RestServiceTest.createDumbPolicySet("testUpdateRootPolicyRefToValidVersion", "4.6");
		testDomainHelper.testAddAndGetPolicy(policySet3);

		IdReferenceType newRootPolicyRef = new IdReferenceType("testUpdateRootPolicyRefToValidVersion", "4.5", null, null);
		propsRes.updateOtherPdpProperties(new PdpPropertiesUpdate(null, newRootPolicyRef));

		// verify update
		final PdpProperties newProps = testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties();
		assertEquals(newProps.getApplicablePolicies().getRootPolicyRef(), newRootPolicyRef);

		// delete other policy versions (must succeed) to check that the root
		// policyRef is not pointing to one of them by mistake
		PolicyResource policyRes = testDomain.getPapResource().getPoliciesResource().getPolicyResource("testUpdateRootPolicyRefToValidVersion");
		policyRes.getPolicyVersionResource("4.4").deletePolicyVersion();
		policyRes.getPolicyVersionResource("4.6").deletePolicyVersion();
	}

	@Test(dependsOnMethods = { "updateRootPolicyRefToValidVersion" })
	public void updateRootPolicyRefToMissingVersion() throws JAXBException
	{
		PdpPropertiesResource propsRes = testDomain.getPapResource().getPdpPropertiesResource();
		PdpProperties props = propsRes.getOtherPdpProperties();

		// get current root policy ID
		final IdReferenceType rootPolicyRef = props.getApplicablePolicies().getRootPolicyRef();

		// point rootPolicyRef to same policy id but different version
		String rootPolicyId = rootPolicyRef.getValue();
		IdReferenceType newRootPolicyRef = new IdReferenceType(rootPolicyId, "0.0.0.1", null, null);
		// MUST FAIL
		try
		{
			propsRes.updateOtherPdpProperties(new PdpPropertiesUpdate(null, newRootPolicyRef));
			fail("Setting rootPolicyRef to missing policy version did not fail as expected.");
		} catch (BadRequestException e)
		{
			// OK
		}

		// make sure rootPolicyRef unchanged
		assertEquals(rootPolicyRef, testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties().getApplicablePolicies().getRootPolicyRef());
	}

	@Test(dependsOnMethods = { "updateRootPolicyRefToValidPolicy" })
	public void setRootPolicyWithGoodRefs() throws JAXBException
	{
		/*
		 * Before putting the empty refPolicySets (to make a policySets with references wrong), first put good root policySet without policy references (in case the current root PolicySet has
		 * references, uploading the empty refPolicySets before will be rejected because it makes the policySet with references invalid)
		 */
		testDomainHelper.resetPdpAndPrp();

		final JAXBElement<PolicySet> jaxbElement = testDomainHelper.unmarshal(new File(RestServiceTest.XACML_SAMPLES_DIR, "pdp/PolicyReference.Valid/refPolicies/pps-employee.xml"), PolicySet.class);
		final PolicySet refPolicySet = jaxbElement.getValue();
		String refPolicyResId = testDomainHelper.testAddAndGetPolicy(refPolicySet);

		// Set root policy referencing ref policy above
		final JAXBElement<PolicySet> jaxbElement2 = testDomainHelper.unmarshal(new File(RestServiceTest.XACML_SAMPLES_DIR, "pdp/PolicyReference.Valid/policy.xml"), PolicySet.class);
		final PolicySet policySetWithRef = jaxbElement2.getValue();
		// Add the policy and point the rootPolicyRef to new policy with refs to
		// instantiate it as root policy (validate, etc.)
		testDomainHelper.setRootPolicy(policySetWithRef, true);

		// Delete referenced policy -> must fail (because root policy is still
		// referencing one of them)
		final PolicyResource refPolicyResource = testDomain.getPapResource().getPoliciesResource().getPolicyResource(refPolicyResId);
		try
		{
			refPolicyResource.deletePolicy();
			fail("Policy used/referenced by root policy was deleted, making root policy invalid");
		} catch (BadRequestException e)
		{
			// Bad request as expected
		}

		/*
		 * Check that referenced policy set was not actually deleted, is still there
		 */
		final PolicySet getPolicyVersion = refPolicyResource.getPolicyVersionResource(refPolicySet.getVersion()).getPolicyVersion();
		assertNotNull(getPolicyVersion);
	}

	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void setRootPolicyWithRefToMissingPolicy() throws JAXBException
	{
		/*
		 * Before putting the empty refPolicySets (to make a policySets with references wrong), first put good root policySet without policy references (in case the current root PolicySet has
		 * references, uploading the empty refPolicySets before will be rejected because it makes the policySet with references invalid)
		 */
		testDomainHelper.resetPdpAndPrp();
		// Get current rootPolicy
		final IdReferenceType rootPolicyRef = testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties().getApplicablePolicies().getRootPolicyRef();

		// Then attempt to put bad root policy set (referenced policysets in
		// Policy(Set)IdReferences do
		// not exist since refPolicySets empty)
		final JAXBElement<PolicySet> policySetWithRefs = testDomainHelper.unmarshal(new File(RestServiceTest.XACML_SAMPLES_DIR, "PolicyReference.Undef/policy.xml"), PolicySet.class);
		try
		{
			testDomainHelper.setRootPolicy(policySetWithRefs.getValue(), true);
			fail("Invalid Root PolicySet (with invalid references) accepted");
		} catch (BadRequestException e)
		{
			// Bad request as expected
		}

		// make sure the rootPolicyRef is unchanged
		assertEquals(rootPolicyRef, testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties().getApplicablePolicies().getRootPolicyRef(),
				"rootPolicyRef changed although root policy update rejected");
	}

	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void setRootPolicyWithCircularRef() throws JAXBException
	{
		/*
		 * Before putting the empty refPolicySets (to make a policySets with references wrong), first put good root policySet without policy references (in case the current root PolicySet has
		 * references, uploading the empty refPolicySets before will be rejected because it makes the policySet with references invalid)
		 */
		testDomainHelper.resetPdpAndPrp();
		// Get current rootPolicy
		final IdReferenceType rootPolicyRef = testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties().getApplicablePolicies().getRootPolicyRef();

		// add refPolicies
		final JAXBElement<PolicySet> refPolicySet = testDomainHelper.unmarshal(new File(RestServiceTest.XACML_SAMPLES_DIR, "PolicyReference.Circular/refPolicies/invalid-pps-employee.xml"),
				PolicySet.class);
		testDomainHelper.testAddAndGetPolicy(refPolicySet.getValue());
		final JAXBElement<PolicySet> refPolicySet2 = testDomainHelper.unmarshal(new File(RestServiceTest.XACML_SAMPLES_DIR, "PolicyReference.Circular/refPolicies/pps-manager.xml"), PolicySet.class);
		testDomainHelper.testAddAndGetPolicy(refPolicySet2.getValue());

		// Then attempt to put bad root policy set (referenced policysets in
		// Policy(Set)IdReferences do
		// not exist since refPolicySets empty)
		final JAXBElement<PolicySet> policySetWithRefs = testDomainHelper.unmarshal(new File(RestServiceTest.XACML_SAMPLES_DIR, "PolicyReference.Circular/policy.xml"), PolicySet.class);
		try
		{
			testDomainHelper.setRootPolicy(policySetWithRefs.getValue(), true);
			fail("Invalid Root PolicySet (with invalid references) accepted");
		} catch (BadRequestException e)
		{
			// Bad request as expected
		}

		// make sure the rootPolicyRef is unchanged
		assertEquals(rootPolicyRef, testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties().getApplicablePolicies().getRootPolicyRef(),
				"rootPolicyRef changed although root policy update rejected");
	}

	/**
	 * This tests property 'org.apache.cxf.stax.maxChildElements' = {@value #XML_MAX_CHILD_ELEMENTS}
	 * 
	 * @throws JAXBException
	 */
	@Test(dependsOnMethods = { "addAndGetPolicy" }, expectedExceptions = NotFoundException.class)
	public void addPolicyWithTooManyChildElements() throws JAXBException
	{
		final JAXBElement<PolicySet> badPolicySetJaxbObj = testDomainHelper.unmarshal(new File(RestServiceTest.XACML_SAMPLES_DIR, "policyWithTooManyChildElements.xml"), PolicySet.class);
		final PolicySet badPolicySet = badPolicySetJaxbObj.getValue();
		try
		{
			testDomainHelper.testAddAndGetPolicy(badPolicySet);
			fail("Invalid PolicySet (too many child elements) accepted");
		} catch (ClientErrorException e)
		{
			assertEquals(e.getResponse().getStatus(), Status.REQUEST_ENTITY_TOO_LARGE.getStatusCode());
		}

		// make sure the policy is not there
		// MUST throw NotFoundException
		testDomain.getPapResource().getPoliciesResource().getPolicyResource(badPolicySet.getPolicySetId()).getPolicyVersions();
	}

	@Test(dependsOnMethods = { "addAndGetPolicy" }, expectedExceptions = NotFoundException.class)
	public void addPolicyTooDeep() throws JAXBException
	{
		final JAXBElement<PolicySet> jaxbObj = testDomainHelper.unmarshal(new File(RestServiceTest.XACML_SAMPLES_DIR, "policyTooDeep.xml"), PolicySet.class);
		final PolicySet badPolicySet = jaxbObj.getValue();
		try
		{
			testDomainHelper.testAddAndGetPolicy(badPolicySet);
			fail("Invalid PolicySet (too deep element(s)) accepted");
		} catch (BadRequestException e)
		{
			// Bad request as expected
		} catch (ClientErrorException e)
		{
			// When using FastInfoset, the error is not Bad Request but 413 Request Entity Too Large
			assertEquals(e.getResponse().getStatus(), Status.REQUEST_ENTITY_TOO_LARGE.getStatusCode());
		}

		// make sure the policy is not there
		// MUST throw NotFoundException
		testDomain.getPapResource().getPoliciesResource().getPolicyResource(badPolicySet.getPolicySetId()).getPolicyVersions();
	}

	/**
	 * Create PDP evaluation test data. Various PolicySets/Requests/Responses from conformance tests.
	 * 
	 * 
	 * @return iterator over test data
	 * @throws URISyntaxException
	 * @throws IOException
	 */
	@DataProvider(name = "pdpTestFiles")
	public Iterator<Object[]> createData() throws URISyntaxException, IOException
	{
		final Collection<Object[]> testParams = new ArrayList<>();
		/*
		 * Each sub-directory of the root directory is data for a specific test. So we configure a test for each directory
		 */
		final File testRootDir = new File(RestServiceTest.XACML_SAMPLES_DIR, "pdp");
		for (final File subDir : testRootDir.listFiles(DIRECTORY_FILTER))
		{
			// specific test's resources directory location, used as parameter
			// to PdpTest(String)
			testParams.add(new Object[] { subDir });
		}

		return testParams.iterator();
	}

	private static void assertNormalizedEquals(Response expectedResponse, Response actualResponseFromPDP) throws JAXBException
	{
		// normalize responses for comparison
		final Response normalizedExpectedResponse = TestUtils.normalizeForComparison(expectedResponse);
		final Response normalizedActualResponse = TestUtils.normalizeForComparison(actualResponseFromPDP);
		assertEquals(normalizedExpectedResponse, normalizedActualResponse, "Actual and expected responses don't match (Status elements removed/ignored for comparison)");
	}

	private void testSetFeature(String featureId, PdpFeatureType featureType, boolean enabled)
	{
		final Feature setFeature = new Feature(featureId, featureType.toString(), enabled);
		final List<Feature> inputFeatures = Collections.singletonList(setFeature);
		List<Feature> outputFeatures = testDomainHelper.updateAndGetPdpFeatures(inputFeatures);
		boolean featureMatched = false;
		final Set<String> features = new HashSet<>();
		for (Feature outputFeature : outputFeatures)
		{
			final String featureID = outputFeature.getValue();
			assertTrue(features.add(featureID), "Duplicate feature: " + featureID);
			if (outputFeature.getType().equals(featureType.toString()) && outputFeature.getValue().equals(featureId))
			{
				assertEquals(outputFeature.isEnabled(), enabled, "Enabled values of input feature (" + featureId + ") and matching output feature don't match");
				featureMatched = true;
			}
		}

		assertTrue(featureMatched, "No feature '" + featureId + "' found");
	}

	/**
	 * Test enable/disable a PDP core feature: XPath eval (AttributeSelector, XPath datatype/functions)
	 */
	@Parameters({ "remote.base.url" })
	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void enableThenDisableXPath(@Optional String remoteAppBaseUrl) throws JAXBException
	{
		testSetFeature(PdpCoreFeature.XPATH_EVAL.toString(), PdpFeatureType.CORE, true);

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			boolean xpathEvalEnabled = testDomainHelper.getPdpConfFromFile().isEnableXPath();
			assertTrue(xpathEvalEnabled, "Failed to enable XPath support in PDP configuration");
		}

		// Disable XPath support
		testSetFeature(PdpCoreFeature.XPATH_EVAL.toString(), PdpFeatureType.CORE, false);

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			boolean xpathEvalEnabled = testDomainHelper.getPdpConfFromFile().isEnableXPath();
			assertFalse(xpathEvalEnabled, "Failed to disable XPath support in PDP configuration");
		}
	}

	/**
	 * Test enable/disable a PDP core feature: strict Attribute Issuer matching
	 */
	@Parameters({ "remote.base.url" })
	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void enableThenDisableStrictAttributeIssuerMatch(@Optional String remoteAppBaseUrl) throws JAXBException
	{
		testSetFeature(PdpCoreFeature.STRICT_ATTRIBUTE_ISSUER_MATCH.toString(), PdpFeatureType.CORE, true);

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			boolean xpathEvalEnabled = testDomainHelper.getPdpConfFromFile().isStrictAttributeIssuerMatch();
			assertTrue(xpathEvalEnabled, "Failed to enable strictAttributeIssuerMatch in PDP configuration");
		}

		// Disable XPath support
		testSetFeature(PdpCoreFeature.STRICT_ATTRIBUTE_ISSUER_MATCH.toString(), PdpFeatureType.CORE, false);

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			boolean xpathEvalEnabled = testDomainHelper.getPdpConfFromFile().isStrictAttributeIssuerMatch();
			assertFalse(xpathEvalEnabled, "Failed to disable strictAttributeIssuerMatch in PDP configuration");
		}
	}

	/**
	 * Test enable/disable a PDP datatype feature (extension for custom datatype): dnsName-value
	 */
	@Parameters({ "remote.base.url" })
	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void enableThenDisableCustomDatatype(@Optional String remoteAppBaseUrl) throws JAXBException
	{
		testSetFeature(TestDNSNameWithPortValue.ID, PdpFeatureType.DATATYPE, true);

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			List<String> customDatatypes = testDomainHelper.getPdpConfFromFile().getAttributeDatatypes();
			assertTrue(customDatatypes.contains(TestDNSNameWithPortValue.ID), "Failed to enable custom datatype '" + TestDNSNameWithPortValue.ID + "' in PDP configuration");
		}

		// Disable XPath support
		testSetFeature(TestDNSNameWithPortValue.ID, PdpFeatureType.DATATYPE, false);

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			List<String> customDatatypes = testDomainHelper.getPdpConfFromFile().getAttributeDatatypes();
			assertFalse(customDatatypes.contains(TestDNSNameWithPortValue.ID), "Failed to disable custom datatype '" + TestDNSNameWithPortValue.ID + "' in PDP configuration");
		}
	}

	/**
	 * Test enable/disable a PDP function feature (extension for custom function): dnsName-value-equal
	 */
	@Parameters({ "remote.base.url" })
	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void enableThenDisableCustomFunction(@Optional String remoteAppBaseUrl) throws JAXBException
	{
		testSetFeature(TestDNSNameValueEqualFunction.ID, PdpFeatureType.FUNCTION, true);

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			List<String> customFunctions = testDomainHelper.getPdpConfFromFile().getFunctions();
			assertTrue(customFunctions.contains(TestDNSNameValueEqualFunction.ID), "Failed to enable custom function '" + TestDNSNameValueEqualFunction.ID + "' in PDP configuration");
		}

		// Disable XPath support
		testSetFeature(TestDNSNameValueEqualFunction.ID, PdpFeatureType.FUNCTION, false);

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			List<String> customFunctions = testDomainHelper.getPdpConfFromFile().getFunctions();
			assertFalse(customFunctions.contains(TestDNSNameValueEqualFunction.ID), "Failed to disable custom function '" + TestDNSNameValueEqualFunction.ID + "' in PDP configuration");
		}
	}

	/**
	 * Test enable/disable a PDP combining algorithm feature (extension for custom policy/rule combining algorithm): on-permit-apply-second
	 */
	@Parameters({ "remote.base.url" })
	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void enableThenDisableCustomCombiningAlgorithm(@Optional String remoteAppBaseUrl) throws JAXBException
	{
		testSetFeature(TestOnPermitApplySecondCombiningAlg.ID, PdpFeatureType.COMBINING_ALGORITHM, true);

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			List<String> customAlgorithms = testDomainHelper.getPdpConfFromFile().getCombiningAlgorithms();
			assertTrue(customAlgorithms.contains(TestOnPermitApplySecondCombiningAlg.ID), "Failed to enable custom combining algorithm '" + TestOnPermitApplySecondCombiningAlg.ID
					+ "' in PDP configuration");
		}

		// Disable XPath support
		testSetFeature(TestOnPermitApplySecondCombiningAlg.ID, PdpFeatureType.COMBINING_ALGORITHM, false);

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			List<String> customAlgorithms = testDomainHelper.getPdpConfFromFile().getCombiningAlgorithms();
			assertFalse(customAlgorithms.contains(TestOnPermitApplySecondCombiningAlg.ID), "Failed to disable custom combining algorithm '" + TestOnPermitApplySecondCombiningAlg.ID
					+ "' in PDP configuration");
		}
	}

	/**
	 * Test enable/disable a PDP result-filter feature (extension for custom XACML decision result filter): Multiple Decision Request Profile / CombinedDecision
	 */
	@Parameters({ "remote.base.url" })
	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void enableMDPResultFilter(@Optional String remoteAppBaseUrl) throws JAXBException
	{
		final Feature mdpFeature = new Feature(TestCombinedDecisionResultFilter.ID, PdpFeatureType.RESULT_FILTER.toString(), true);
		final List<Feature> inputFeatures = Collections.singletonList(mdpFeature);
		List<Feature> outputFeatures = testDomainHelper.updateAndGetPdpFeatures(inputFeatures);
		boolean mdpFeatureFound = false;
		final Set<String> features = new HashSet<>();
		for (Feature outputFeature : outputFeatures)
		{
			final String featureID = outputFeature.getValue();
			assertTrue(features.add(featureID), "Duplicate feature: " + featureID);
			if (outputFeature.getType().equals(FlatFileBasedDomainsDAO.PdpFeatureType.RESULT_FILTER.toString()))
			{
				if (outputFeature.getValue().equals(TestCombinedDecisionResultFilter.ID))
				{
					assertTrue(outputFeature.isEnabled(), "Returned feature (" + TestCombinedDecisionResultFilter.ID + ") disabled!");
					mdpFeatureFound = true;
				} else
				{
					// another requestFilter. Must not be enabled.
					assertFalse(outputFeature.isEnabled(), "Returned other resultFilter (not MDP) feature enabled! (" + featureID + ")");
				}
			}
		}

		assertTrue(mdpFeatureFound, "No enabled feature '" + TestCombinedDecisionResultFilter.ID + "' found");

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			String resultFilterId = testDomainHelper.getPdpConfFromFile().getResultFilter();
			assertEquals(resultFilterId, TestCombinedDecisionResultFilter.ID, "Result filter in PDP conf file does not match features " + inputFeatures);
		}
	}

	@Parameters({ "remote.base.url" })
	@Test(dependsOnMethods = { "enableMDPResultFilter" })
	public void disableMDPResultFilter(@Optional String remoteAppBaseUrl) throws JAXBException
	{
		final Feature mdpFeature = new Feature(TestCombinedDecisionResultFilter.ID, PdpFeatureType.RESULT_FILTER.toString(), false);
		final List<Feature> inputFeatures = Collections.singletonList(mdpFeature);
		List<Feature> outputFeatures = testDomainHelper.updateAndGetPdpFeatures(inputFeatures);
		boolean mdpFeatureFound = false;
		final Set<String> features = new HashSet<>();
		for (Feature outputFeature : outputFeatures)
		{
			final String featureID = outputFeature.getValue();
			assertTrue(features.add(featureID), "Duplicate feature: " + featureID);
			if (outputFeature.getType().equals(PdpFeatureType.RESULT_FILTER.toString()))
			{
				assertFalse(outputFeature.isEnabled(), "Returned resultFilter feature enabled! (" + featureID + ")");
				if (featureID.equals(TestCombinedDecisionResultFilter.ID))
				{
					mdpFeatureFound = true;
				}
			}
		}

		assertTrue(mdpFeatureFound, "No feature '" + TestCombinedDecisionResultFilter.ID + "' found");

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			String resultFilterId = testDomainHelper.getPdpConfFromFile().getResultFilter();
			Assert.assertNull(resultFilterId, "Result filter ('" + resultFilterId + "') != null in PDP configuration file, therefore does not match features " + inputFeatures);
		}
	}

	/**
	 * Test enable/disable a PDP request-filter feature: Multiple Decision Request Profile / repeated attribute categories
	 */
	@Parameters({ "remote.base.url" })
	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void enableMDPRequestFilter(@Optional String remoteAppBaseUrl) throws JAXBException
	{
		final Feature mdpFeature = new Feature(MultiDecisionRequestFilter.LaxFilterFactory.ID, PdpFeatureType.REQUEST_FILTER.toString(), true);
		final List<Feature> inputFeatures = Collections.singletonList(mdpFeature);
		List<Feature> outputFeatures = testDomainHelper.updateAndGetPdpFeatures(inputFeatures);
		boolean mdpFeatureFound = false;
		final Set<String> features = new HashSet<>();
		for (Feature outputFeature : outputFeatures)
		{
			final String featureID = outputFeature.getValue();
			assertTrue(features.add(featureID), "Duplicate feature: " + featureID);
			if (outputFeature.getType().equals(FlatFileBasedDomainsDAO.PdpFeatureType.REQUEST_FILTER.toString()))
			{
				if (outputFeature.getValue().equals(MultiDecisionRequestFilter.LaxFilterFactory.ID))
				{
					assertTrue(outputFeature.isEnabled(), "Returned feature (" + MultiDecisionRequestFilter.LaxFilterFactory.ID + ") disabled!");
					mdpFeatureFound = true;
				} else
				{
					// another requestFilter. Must not be enabled.
					assertFalse(outputFeature.isEnabled(), "Returned other requestFilter (not MDP) feature enabled! (" + featureID + ")");
				}
			}
		}

		assertTrue(mdpFeatureFound, "No enabled feature '" + MultiDecisionRequestFilter.LaxFilterFactory.ID + "' found");

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			String requestFilterId = testDomainHelper.getPdpConfFromFile().getRequestFilter();
			assertEquals(requestFilterId, MultiDecisionRequestFilter.LaxFilterFactory.ID, "Request filter in PDP conf file does not match features " + inputFeatures);
		}
	}

	@Parameters({ "remote.base.url" })
	@Test(dependsOnMethods = { "enableMDPRequestFilter" })
	public void disableMDPRequestFilter(@Optional String remoteAppBaseUrl) throws JAXBException
	{
		final Feature mdpFeature = new Feature(MultiDecisionRequestFilter.LaxFilterFactory.ID, PdpFeatureType.REQUEST_FILTER.toString(), false);
		final List<Feature> inputFeatures = Collections.singletonList(mdpFeature);
		List<Feature> outputFeatures = testDomainHelper.updateAndGetPdpFeatures(inputFeatures);
		boolean mdpFeatureFound = false;
		final Set<String> features = new HashSet<>();
		for (Feature outputFeature : outputFeatures)
		{
			final String featureID = outputFeature.getValue();
			assertTrue(features.add(featureID), "Duplicate feature: " + featureID);
			if (outputFeature.getType().equals(FlatFileBasedDomainsDAO.PdpFeatureType.REQUEST_FILTER.toString()))
			{
				if (featureID.equals(DefaultRequestFilter.LaxFilterFactory.ID))
				{
					// another requestFilter. Must not be enabled.
					assertTrue(outputFeature.isEnabled(), "Returned default requestFilter feature disabled! (" + featureID + ")");
				} else
				{
					assertFalse(outputFeature.isEnabled(), "Returned non-default requestFilter feature enabled! (" + featureID + ")");
					if (featureID.equals(MultiDecisionRequestFilter.LaxFilterFactory.ID))
					{
						mdpFeatureFound = true;
					}
				}
			}
		}

		assertTrue(mdpFeatureFound, "No feature '" + MultiDecisionRequestFilter.LaxFilterFactory.ID + "' found");

		// verify on disk if the server is local
		if (remoteAppBaseUrl == null || remoteAppBaseUrl.isEmpty())
		{
			String requestFilterId = testDomainHelper.getPdpConfFromFile().getRequestFilter();
			assertEquals(requestFilterId, DefaultRequestFilter.LaxFilterFactory.ID, "Request filter in PDP conf file does not match features " + inputFeatures);
		}
	}

	private void requestPDP(File testDirectory, List<Feature> pdpFeaturesToEnable, boolean isPdpRemote) throws JAXBException
	{
		testDomainHelper.resetPdpAndPrp(pdpFeaturesToEnable);
		// reset attribute providers
		testDomain.getPapResource().getAttributeProvidersResource().updateAttributeProviderList(new AttributeProviders(Collections.<AbstractAttributeProvider> emptyList()));

		LOGGER.debug("Starting PDP test of directory '{}'", testDirectory);

		final File attributeProviderFile = new File(testDirectory, RestServiceTest.TEST_ATTRIBUTE_PROVIDER_FILENAME);
		if (attributeProviderFile.exists())
		{
			/*
			 * This requires Attribute Provider extension to be deployed/configured in advance on the PDP. If we are testing a remote PDP, this may not be done, in which case we would get error 400
			 * when trying to use it; so we skip this test in this case.
			 */
			if (isPdpRemote)
			{
				return;
			}

			JAXBElement<AbstractAttributeProvider> jaxbElt = testDomainHelper.unmarshal(attributeProviderFile, AbstractAttributeProvider.class);
			testDomain.getPapResource().getAttributeProvidersResource().updateAttributeProviderList(new AttributeProviders(Collections.<AbstractAttributeProvider> singletonList(jaxbElt.getValue())));
		}

		final File refPoliciesDir = new File(testDirectory, RestServiceTest.TEST_REF_POLICIES_DIRECTORY_NAME);
		if (refPoliciesDir.exists() && refPoliciesDir.isDirectory())
		{
			for (final File policyFile : refPoliciesDir.listFiles())
			{
				if (policyFile.isFile())
				{
					final JAXBElement<PolicySet> policy = testDomainHelper.unmarshal(policyFile, PolicySet.class);
					testDomainHelper.testAddAndGetPolicy(policy.getValue());
					LOGGER.debug("Added policy from file: " + policyFile);
				}
			}
		}

		final JAXBElement<PolicySet> policy = testDomainHelper.unmarshal(new File(testDirectory, RestServiceTest.TEST_POLICY_FILENAME), PolicySet.class);
		// Add the policy and point the rootPolicyRef to new policy with refs to
		// instantiate it as root policy (validate, etc.)
		testDomainHelper.setRootPolicy(policy.getValue(), true);

		final JAXBElement<Request> xacmlReq = testDomainHelper.unmarshal(new File(testDirectory, RestServiceTest.REQUEST_FILENAME), Request.class);
		final JAXBElement<Response> expectedResponse = testDomainHelper.unmarshal(new File(testDirectory, RestServiceTest.EXPECTED_RESPONSE_FILENAME), Response.class);
		final Response actualResponse = testDomain.getPdpResource().requestPolicyDecision(xacmlReq.getValue());
		// final Response actualResponse = testDomainFI.getPdpResource().requestPolicyDecision(xacmlReq.getValue());

		assertNormalizedEquals(expectedResponse.getValue(), actualResponse);
	}

	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" }, dataProvider = "pdpTestFiles")
	public void requestPDPWithoutMDP(File testDirectory) throws JAXBException
	{
		// disable all features (incl. MDP) of PDP
		requestPDP(testDirectory, null, !IS_EMBEDDED_SERVER_STARTED.get());
	}

	@Parameters({ "remote.base.url" })
	@Test(dependsOnMethods = { "enableMDPRequestFilter" })
	public void requestPDPWithMDP(@Optional String remoteAppBaseUrl) throws JAXBException
	{
		// enable MDP on PDP
		final Feature mdpFeature = new Feature(MultiDecisionRequestFilter.LaxFilterFactory.ID, FlatFileBasedDomainsDAO.PdpFeatureType.REQUEST_FILTER.toString(), true);
		final List<Feature> inputFeatures = Collections.singletonList(mdpFeature);
		final File testDirectory = new File(RestServiceTest.XACML_SAMPLES_DIR, "IIIE302(PolicySet)");
		requestPDP(testDirectory, inputFeatures, !IS_EMBEDDED_SERVER_STARTED.get());
	}

	private void verifySyncAfterPdpConfFileModification(IdReferenceType newRootPolicyRef) throws JAXBException
	{
		// check PDP returned policy identifier
		final JAXBElement<Request> jaxbXacmlReq = testDomainHelper.unmarshal(new File(RestServiceTest.XACML_IIIG301_PDP_TEST_DIR, RestServiceTest.REQUEST_FILENAME), Request.class);
		final Response actualResponse = testDomain.getPdpResource().requestPolicyDecision(jaxbXacmlReq.getValue());
		for (JAXBElement<IdReferenceType> jaxbElt : actualResponse.getResults().get(0).getPolicyIdentifierList().getPolicyIdReferencesAndPolicySetIdReferences())
		{
			String tagLocalName = jaxbElt.getName().getLocalPart();
			if (tagLocalName.equals("PolicySetIdReference"))
			{
				assertEquals(jaxbElt.getValue(), newRootPolicyRef,
						"Manual sync with API getOtherPdpProperties() failed: PolicySetIdReference returned by PDP does not match the root policyRef in PDP configuration file");
				return;
			}
		}

		fail("Manual sync with API getOtherPdpProperties() failed: PolicySetIdReference returned by PDP does not match the root policyRef in PDP configuration file");
	}

	@Parameters({ "remote.base.url", "legacy.fs" })
	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void headRootPolicyRefAfterChangingPdpConfFile(@Optional String remoteAppBaseUrl, @Optional("false") Boolean isFilesystemLegacy) throws JAXBException, InterruptedException
	{
		// skip this if server not started locally (files not local)
		if (remoteAppBaseUrl != null && !remoteAppBaseUrl.isEmpty())
		{
			return;
		}

		final IdReferenceType newRootPolicyRef = testDomainHelper.modifyRootPolicyRefInPdpConfFile(isFilesystemLegacy);

		// Manual sync via HEAD /domains/{domainId}/pap/properties
		final javax.ws.rs.core.Response response = httpHeadClient.reset().path("domains").path(testDomainId).path("pap").path("pdp.properties").accept(MediaType.APPLICATION_XML).head();
		assertEquals(response.getStatus(), javax.ws.rs.core.Response.Status.OK.getStatusCode(), "HEAD /domains/" + testDomainId + "/pap/pdp.properties failed");

		verifySyncAfterPdpConfFileModification(newRootPolicyRef);
	}

	@Parameters({ "remote.base.url", "legacy.fs" })
	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void getRootPolicyRefAfterChangingPdpConfFile(@Optional String remoteAppBaseUrl, @Optional("false") Boolean isFilesystemLegacy) throws JAXBException, InterruptedException
	{
		// skip this if server not started locally (files not local)
		if (remoteAppBaseUrl != null && !remoteAppBaseUrl.isEmpty())
		{
			return;
		}

		final IdReferenceType newRootPolicyRef = testDomainHelper.modifyRootPolicyRefInPdpConfFile(isFilesystemLegacy);

		// Manual sync via GET /domains/{domainId}/pap/properties
		long timeBeforeSync = System.currentTimeMillis();
		PdpProperties pdpProps = testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties();
		long lastModifiedTime = pdpProps.getLastModifiedTime().toGregorianCalendar().getTimeInMillis();
		assertTrue(lastModifiedTime >= timeBeforeSync, "Manual sync with API getOtherPdpProperties() failed: returned lastModifiedTime is not up-to-date");

		assertEquals(pdpProps.getApplicablePolicies().getRootPolicyRef(), newRootPolicyRef,
				"Manual sync with API getOtherPdpProperties() failed: returned root policyRef does not match the one in PDP configuration file");

		verifySyncAfterPdpConfFileModification(newRootPolicyRef);
	}

	@Parameters({ "remote.base.url", "legacy.fs" })
	@Test(dependsOnMethods = { "setRootPolicyWithCircularRef", "getRootPolicyRefAfterChangingPdpConfFile" }, expectedExceptions = BadRequestException.class)
	public void setRootPolicyWithTooDeepPolicyRef(@Optional String remoteAppBaseUrl, @Optional("false") Boolean isFilesystemLegacy) throws JAXBException, InterruptedException
	{
		// skip this if server not started locally (files not local)
		if (remoteAppBaseUrl != null && !remoteAppBaseUrl.isEmpty())
		{
			return;
		}

		// set PDP configuration locally to maxPolicyRefDepth = 2
		testDomainHelper.modifyMaxPolicyRefDepthInPdpConfFile(isFilesystemLegacy, 2);

		// add refPolicies
		final JAXBElement<PolicySet> refPolicySet = testDomainHelper.unmarshal(new File(RestServiceTest.XACML_SAMPLES_DIR, "PolicyReference.TooDeep/refPolicies/pps-employee.xml"), PolicySet.class);
		testDomainHelper.testAddAndGetPolicy(refPolicySet.getValue());
		final JAXBElement<PolicySet> refPolicySet2 = testDomainHelper.unmarshal(new File(RestServiceTest.XACML_SAMPLES_DIR, "PolicyReference.TooDeep/refPolicies/pps-manager.xml"), PolicySet.class);
		testDomainHelper.testAddAndGetPolicy(refPolicySet2.getValue());

		// Then attempt to put bad root policy set (referenced policysets in
		// Policy(Set)IdReferences do
		// not exist since refPolicySets empty)
		final JAXBElement<PolicySet> policySetWithRefs = testDomainHelper.unmarshal(new File(RestServiceTest.XACML_SAMPLES_DIR, "PolicyReference.TooDeep/policy.xml"), PolicySet.class);
		testDomainHelper.setRootPolicy(policySetWithRefs.getValue(), true);
	}

	@Parameters({ "remote.base.url", "legacy.fs" })
	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void getPdpPropertiesAfterModifyingUsedPolicyDirectory(@Optional String remoteAppBaseUrl, @Optional("false") Boolean isFileSystemLegacy) throws JAXBException, InterruptedException
	{
		// skip this if server not started locally (files not local)
		if (remoteAppBaseUrl != null && !remoteAppBaseUrl.isEmpty())
		{
			return;
		}

		final File inputRefPolicyFile = new File(RestServiceTest.XACML_POLICYREFS_PDP_TEST_DIR, RestServiceTest.TEST_REF_POLICIES_DIRECTORY_NAME + "/pps-employee.xml");
		final File inputRootPolicyFile = new File(RestServiceTest.XACML_POLICYREFS_PDP_TEST_DIR, RestServiceTest.TEST_DEFAULT_POLICYSET_FILENAME);
		final IdReferenceType newRefPolicySetRef = testDomainHelper.addRootPolicyWithRefAndUpdate(inputRootPolicyFile, inputRefPolicyFile, false, isFileSystemLegacy);

		// Manual sync of PDP's active policies via GET /domains/{domainId}/pap/properties
		long timeBeforeSync = System.currentTimeMillis();
		PdpProperties pdpProps = testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties();
		long timeAfterSync = System.currentTimeMillis();

		// check new PDP lastModifiedTime
		long lastModifiedTime = pdpProps.getLastModifiedTime().toGregorianCalendar().getTimeInMillis();
		if (LOGGER.isDebugEnabled())
		{
			LOGGER.debug("Domain '{}': PDP lastmodifiedtime = {}", testDomainId, RestServiceTest.UTC_DATE_WITH_MILLIS_FORMATTER.format(new Date(lastModifiedTime)));
		}

		assertTrue(
				lastModifiedTime >= timeBeforeSync && lastModifiedTime <= timeAfterSync,
				"Manual sync with API Domain('" + testDomainId + "')#getOtherPdpProperties() failed: lastModifiedTime ("
						+ RestServiceTest.UTC_DATE_WITH_MILLIS_FORMATTER.format(new Date(lastModifiedTime))
						+ ") returned by getOtherPdpProperties() does not match the time when getPolicies() was called. Expected to be in range ["
						+ RestServiceTest.UTC_DATE_WITH_MILLIS_FORMATTER.format(new Date(timeBeforeSync)) + ", " + RestServiceTest.UTC_DATE_WITH_MILLIS_FORMATTER.format(new Date(timeAfterSync)) + "]");
		// check enabled policies
		assertTrue(pdpProps.getApplicablePolicies().getRefPolicyReves().contains(newRefPolicySetRef), "Manual sync with API Domain('" + testDomainId
				+ "')#getOtherPdpProperties() failed: <refPolicyRef>s returned by getOtherPdpProperties() ( = " + pdpProps.getApplicablePolicies().getRefPolicyReves()
				+ ") does not contain last applicable refpolicy version created on disk: " + newRefPolicySetRef);

		// Redo the same but updating the root policy version on disk this time
		final IdReferenceType newRootPolicySetRef = testDomainHelper.addRootPolicyWithRefAndUpdate(inputRootPolicyFile, inputRefPolicyFile, true, isFileSystemLegacy);

		// Manual sync of PDP's active policies via GET /domains/{domainId}/pap/properties
		long timeBeforeSync2 = System.currentTimeMillis();
		PdpProperties pdpProps2 = testDomain.getPapResource().getPdpPropertiesResource().getOtherPdpProperties();
		long timeAfterSync2 = System.currentTimeMillis();
		final long lastModifiedTime2 = pdpProps2.getLastModifiedTime().toGregorianCalendar().getTimeInMillis();
		if (LOGGER.isDebugEnabled())
		{
			LOGGER.debug("Domain '{}': PDP lastmodifiedtime = {}", testDomainId, RestServiceTest.UTC_DATE_WITH_MILLIS_FORMATTER.format(new Date(lastModifiedTime2)));
		}

		assertTrue(
				lastModifiedTime2 >= timeBeforeSync2 && lastModifiedTime2 <= timeAfterSync2,
				"Manual sync with API Domain('" + testDomainId + "')#getOtherPdpProperties() failed: lastModifiedTime ("
						+ RestServiceTest.UTC_DATE_WITH_MILLIS_FORMATTER.format(new Date(lastModifiedTime2))
						+ ") returned by getOtherPdpProperties() does not match the time when getPolicies() was called. Expected to be in range ["
						+ RestServiceTest.UTC_DATE_WITH_MILLIS_FORMATTER.format(new Date(timeBeforeSync2)) + ", " + RestServiceTest.UTC_DATE_WITH_MILLIS_FORMATTER.format(new Date(timeAfterSync2))
						+ "]");
		// check enabled policies
		assertEquals(pdpProps2.getApplicablePolicies().getRootPolicyRef(), newRootPolicySetRef, "Manual sync with API Domain('" + testDomainId
				+ "')#getOtherPdpProperties() failed: rootPolicyRef returned by getOtherPdpProperties() ( = " + pdpProps2.getApplicablePolicies().getRootPolicyRef()
				+ ") does not match last applicable root policy version created on disk: " + newRootPolicySetRef);
	}

	private void verifyPdpReturnedPolicies(IdReferenceType expectedPolicyRef) throws JAXBException
	{
		/*
		 * We cannot check again with /domain/{domainId}/pap/properties because it will sync again and this is not what we intend to test here.
		 */
		// Check PDP returned policy identifiers
		final JAXBElement<Request> jaxbXacmlReq = testDomainHelper.unmarshal(new File(RestServiceTest.XACML_POLICYREFS_PDP_TEST_DIR, "requestPolicyIdentifiers.xml"), Request.class);
		final Request xacmlReq = jaxbXacmlReq.getValue();
		final Response actualResponse = testDomain.getPdpResource().requestPolicyDecision(xacmlReq);
		boolean isNewRefPolicyRefMatched = false;
		final List<JAXBElement<IdReferenceType>> jaxbPolicyRefs = actualResponse.getResults().get(0).getPolicyIdentifierList().getPolicyIdReferencesAndPolicySetIdReferences();
		final List<IdReferenceType> returnedPolicyIdentifiers = new ArrayList<>();
		for (JAXBElement<IdReferenceType> jaxbPolicyRef : jaxbPolicyRefs)
		{
			String tagLocalName = jaxbPolicyRef.getName().getLocalPart();
			if (tagLocalName.equals("PolicySetIdReference"))
			{
				final IdReferenceType idRef = jaxbPolicyRef.getValue();
				returnedPolicyIdentifiers.add(idRef);
				if (idRef.equals(expectedPolicyRef))
				{
					isNewRefPolicyRefMatched = true;
				}
			}
		}

		assertTrue(isNewRefPolicyRefMatched, "Manual sync with API Domain('" + testDomainId + "')#getPolicies() failed: new policy version created on disk (" + expectedPolicyRef
				+ ") is not in PolicySetIdReferences returned by PDP: " + returnedPolicyIdentifiers);

	}

	@Parameters({ "remote.base.url", "legacy.fs" })
	@Test(dependsOnMethods = { "getPdpPropertiesAfterModifyingUsedPolicyDirectory" })
	public void headPdpPropertiesAfterModifyingUsedPolicyDirectory(@Optional String remoteAppBaseUrl, @Optional("false") Boolean isFileSystemLegacy) throws JAXBException, InterruptedException
	{
		// skip this if server not started locally (files not local)
		if (remoteAppBaseUrl != null && !remoteAppBaseUrl.isEmpty())
		{
			return;
		}

		final File inputRefPolicyFile = new File(RestServiceTest.XACML_POLICYREFS_PDP_TEST_DIR, RestServiceTest.TEST_REF_POLICIES_DIRECTORY_NAME + "/pps-employee.xml");
		final File inputRootPolicyFile = new File(RestServiceTest.XACML_POLICYREFS_PDP_TEST_DIR, RestServiceTest.TEST_DEFAULT_POLICYSET_FILENAME);
		final IdReferenceType newRefPolicySetRef = testDomainHelper.addRootPolicyWithRefAndUpdate(inputRootPolicyFile, inputRefPolicyFile, false, isFileSystemLegacy);

		// Manual sync via HEAD /domains/{domainId}/pap/properties
		final javax.ws.rs.core.Response response = httpHeadClient.reset().path("domains").path(testDomainId).path("pap").path("pdp.properties").accept(MediaType.APPLICATION_XML).head();
		assertEquals(response.getStatus(), javax.ws.rs.core.Response.Status.OK.getStatusCode(), "HEAD /domains/" + testDomainId + "/pap/pdp.properties failed");

		verifyPdpReturnedPolicies(newRefPolicySetRef);

		// Redo the same but adding new root policy version on disk this time
		final IdReferenceType newRootPolicySetRef = testDomainHelper.addRootPolicyWithRefAndUpdate(inputRootPolicyFile, inputRefPolicyFile, true, isFileSystemLegacy);

		// Manual sync of PDP's active policies via GET /domains/{domainId}/pap/policies
		testDomain.getPapResource().getPoliciesResource().getPolicies().getLinks();

		verifyPdpReturnedPolicies(newRootPolicySetRef);
	}

	@Parameters({ "remote.base.url", "legacy.fs" })
	@Test(dependsOnMethods = { "setRootPolicyWithGoodRefs" })
	public void getPoliciesAfterModifyingUsedPolicyDirectory(@Optional String remoteAppBaseUrl, @Optional("false") Boolean isFilesystemLegacy) throws JAXBException, InterruptedException
	{
		// skip this if server not started locally (files not local)
		if (remoteAppBaseUrl != null && !remoteAppBaseUrl.isEmpty())
		{
			return;
		}

		final File inputRefPolicyFile = new File(RestServiceTest.XACML_POLICYREFS_PDP_TEST_DIR, RestServiceTest.TEST_REF_POLICIES_DIRECTORY_NAME + "/pps-employee.xml");
		final File inputRootPolicyFile = new File(RestServiceTest.XACML_POLICYREFS_PDP_TEST_DIR, RestServiceTest.TEST_DEFAULT_POLICYSET_FILENAME);
		final IdReferenceType newRefPolicySetRef = testDomainHelper.addRootPolicyWithRefAndUpdate(inputRootPolicyFile, inputRefPolicyFile, false, isFilesystemLegacy);

		// Manual sync of PDP's active policies via GET /domains/{domainId}/pap/policies
		testDomain.getPapResource().getPoliciesResource().getPolicies().getLinks();

		verifyPdpReturnedPolicies(newRefPolicySetRef);

		// Redo the same but adding new root policy version on disk this time
		final IdReferenceType newRootPolicySetRef = testDomainHelper.addRootPolicyWithRefAndUpdate(inputRootPolicyFile, inputRefPolicyFile, true, isFilesystemLegacy);

		// Manual sync of PDP's active policies via GET /domains/{domainId}/pap/policies
		testDomain.getPapResource().getPoliciesResource().getPolicies().getLinks();

		verifyPdpReturnedPolicies(newRootPolicySetRef);
	}
}
