/**
 * Mule Anypoint Template
 * Copyright (c) MuleSoft, Inc.
 * All rights reserved.  http://www.mulesoft.com
 */

package org.mule.templates.integration;

import static org.junit.Assert.assertEquals;
import static org.mule.templates.builders.SfdcObjectBuilder.anAccount;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mule.MessageExchangePattern;
import org.mule.api.MuleEvent;
import org.mule.api.MuleException;
import org.mule.context.notification.NotificationException;
import org.mule.processor.chain.SubflowInterceptingChainLifecycleWrapper;
import org.mule.templates.builders.SfdcObjectBuilder;
import org.mule.templates.test.utils.ListenerProbe;
import org.mule.templates.db.MySQLDbCreator;

import com.mulesoft.module.batch.BatchTestHelper;
import com.sforce.soap.partner.SaveResult;

/**
 * The objective of this class is to validate the correct behavior of the
 * Anypoint Template that make calls to external systems.
 * 
 */
public class BusinessLogicIT extends AbstractTemplateTestCase {
	
	private BatchTestHelper helper;
	private static final String PATH_TO_TEST_PROPERTIES = "./src/test/resources/mule.test.properties";
	private static final String PATH_TO_SQL_SCRIPT = "src/main/resources/account.sql";
	private static final String DATABASE_NAME = "SFDC2DBAccountBroadcast"
			+ new Long(new Date().getTime()).toString();
	private static final MySQLDbCreator DBCREATOR = new MySQLDbCreator(
			DATABASE_NAME, PATH_TO_SQL_SCRIPT, PATH_TO_TEST_PROPERTIES);
	private List<Map<String, Object>> createdAccountsInA = new ArrayList<Map<String, Object>>();
	private SubflowInterceptingChainLifecycleWrapper selectAccountFromDBFlow;

	@BeforeClass
	public static void beforeClass() {
		DBCREATOR.setUpDatabase();
		System.setProperty("database.url", DBCREATOR.getDatabaseUrlWithName());
		System.setProperty("trigger.policy", "poll");
	}

	@AfterClass
	public static void shutDown() {
		System.clearProperty("trigger.policy");
	}

	@Before
	public void setUp() throws Exception {
		stopFlowSchedulers(POLL_FLOW_NAME);
		registerListeners();
		helper = new BatchTestHelper(muleContext);
		retrieveAccountFromBFlow = getSubFlow("retrieveAccountFlow");
		retrieveAccountFromBFlow.initialise();
		selectAccountFromDBFlow = getSubFlow("selectAccountFromDB");
		createEntities();
	}

	@After
	public void tearDown() throws Exception {
		stopFlowSchedulers(POLL_FLOW_NAME);
		deleteEntities();
		DBCREATOR.tearDownDataBase();
	}

	@Test
	public void testMainFlow() throws Exception {
		runSchedulersOnce(POLL_FLOW_NAME);
		waitForPollToRun();

		helper.awaitJobTermination(TIMEOUT_SEC * 1000, 8000);
		helper.assertJobWasSuccessful();

		Thread.sleep(5000);
		
		assertEquals("The first account should not have been sync to SFDC",
				null,
				invokeRetrieveFlow(retrieveAccountFromBFlow,
						createdAccountsInA.get(0)));

		assertEquals("The second account should not have been sync to SFDC",
				null,
				invokeRetrieveFlow(retrieveAccountFromBFlow,
						createdAccountsInA.get(1)));

		assertEquals("The third account should have been sync to SFDC",
				createdAccountsInA.get(2).get("Name"),
				invokeRetrieveFlow(
						retrieveAccountFromBFlow, 
						createdAccountsInA.get(2)).get("Name"));
	
		assertEquals("The Fourth account should have been sync to SFDC",
				createdAccountsInA.get(3).get("Name"),
				invokeRetrieveFlow(
						retrieveAccountFromBFlow, 
						createdAccountsInA.get(3)).get("Name"));

		
		assertEquals("The Fourth account NumberOfEmployees should have been sync to SFDC",
				createdAccountsInA.get(3).get("NumberOfEmployees"),
				Integer.parseInt((String) invokeRetrieveFlow(
						retrieveAccountFromBFlow, 
						createdAccountsInA.get(3)).get("NumberOfEmployees")));
		
		
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> payloadDb = (List<Map<String, Object>>) selectAccountFromDBFlow
				.process(
						getTestEvent(createdAccountsInA.get(2),
								MessageExchangePattern.REQUEST_RESPONSE))
				.getMessage().getPayload();

		assertEquals("The third account should have been sync to DB", 
				1,
				payloadDb.size());
		
		assertEquals("The third account SalesforceId should match to DB",
				createdAccountsInA.get(2).get("Id"), 
				payloadDb.get(0).get("salesforceId"));
		
		assertEquals("The third account name should match to DB",
				createdAccountsInA.get(2).get("Name"), 
				payloadDb.get(0).get("name"));
		

		@SuppressWarnings("unchecked")
		final List<Map<String, Object>> payloadDb2 = (List<Map<String, Object>>) selectAccountFromDBFlow
				.process(
						getTestEvent(createdAccountsInA.get(3),
								MessageExchangePattern.REQUEST_RESPONSE))
				.getMessage().getPayload();
		
		assertEquals("The fourth account should have been sync to DB", 
				1,
				payloadDb2.size());
		
		assertEquals("The fourth account SalesforceId should match to DB",
				createdAccountsInA.get(3).get("Id"),
				payloadDb2.get(0).get("salesforceId"));
		
		assertEquals("The fourth account name should match to DB",
				createdAccountsInA.get(3).get("Name"), 
				payloadDb2.get(0).get("name"));
	}

	private void registerListeners() throws NotificationException {
		muleContext.registerListener(pipelineListener);
	}

	private void waitForPollToRun() {
		pollProber.check(new ListenerProbe(pipelineListener));
	}

	@SuppressWarnings("unchecked")
	private void createEntities() throws MuleException, Exception {
		SubflowInterceptingChainLifecycleWrapper createAccountInBFlow = getSubFlow("createAccountFlowB");
		createAccountInBFlow.initialise();

		SfdcObjectBuilder updateAccount = anAccount().with("Name",
				buildUniqueName(TEMPLATE_NAME, "DemoUpdateAccount")).with(
				"Industry", "Education");

		List<Map<String, Object>> createdAccountInB = new ArrayList<Map<String, Object>>();
		createdAccountInB.add(updateAccount.with("NumberOfEmployees", 17000)
				.build());
		createAccountInBFlow.process(getTestEvent(createdAccountInB,
				MessageExchangePattern.REQUEST_RESPONSE));

		SubflowInterceptingChainLifecycleWrapper createAccountInAFlow = getSubFlow("createAccountFlowA");
		createAccountInAFlow.initialise();

		createdAccountsInA.add(anAccount()
				.with("Name",
						buildUniqueName(TEMPLATE_NAME,
								"DemoFilterIndustryAccount"))
				.with("Industry", "Insurance").with("NumberOfEmployees", 1700)
				.build());

		createdAccountsInA.add(anAccount()
				.with("Name",
						buildUniqueName(TEMPLATE_NAME,
								"DemoFilterIndustryAccount"))
				.with("Industry", "Government").with("NumberOfEmployees", 2500)
				.build());

		createdAccountsInA.add(anAccount()
				.with("Name",
						buildUniqueName(TEMPLATE_NAME, "DemoCreateAccount"))
				.with("Industry", "Government")
				.with("NumberOfEmployees", 18000).build());

		createdAccountsInA.add(updateAccount.with("NumberOfEmployees", 12000)
				.with("Industry", "Education").build());

		final MuleEvent event = createAccountInAFlow.process(getTestEvent(
				createdAccountsInA, MessageExchangePattern.REQUEST_RESPONSE));
		final List<SaveResult> results = (List<SaveResult>) event.getMessage()
				.getPayload();
		int i = 0;
		for (SaveResult result : results) {
			Map<String, Object> accountInA = createdAccountsInA.get(i);
			accountInA.put("Id", result.getId());
			i++;
		}
	}

	private void deleteEntities() throws MuleException, Exception {
		SubflowInterceptingChainLifecycleWrapper deleteAccountFromAflow = getSubFlow("deleteAccountFromAFlow");
		deleteAccountFromAflow.initialise();

		final List<Object> idList = new ArrayList<Object>();
		for (final Map<String, Object> c : createdAccountsInA) {
			idList.add(c.get("Id"));
		}
		deleteAccountFromAflow.process(getTestEvent(idList,
				MessageExchangePattern.REQUEST_RESPONSE));

		SubflowInterceptingChainLifecycleWrapper deleteAccountFromBflow = getSubFlow("deleteAccountFromBFlow");
		deleteAccountFromBflow.initialise();

		idList.clear();
		for (final Map<String, Object> createdAccount : createdAccountsInA) {
			final Map<String, Object> account = invokeRetrieveFlow(
					retrieveAccountFromBFlow, createdAccount);
			if (account != null) {
				idList.add(account.get("Id"));
			}
		}
		deleteAccountFromBflow.process(getTestEvent(idList,
				MessageExchangePattern.REQUEST_RESPONSE));
	}
}