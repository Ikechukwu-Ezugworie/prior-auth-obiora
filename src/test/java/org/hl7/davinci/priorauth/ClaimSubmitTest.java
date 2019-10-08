package org.hl7.davinci.priorauth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import ca.uhn.fhir.validation.ValidationResult;
import okhttp3.MediaType;

@RunWith(SpringRunner.class)
@TestPropertySource(properties = "server.servlet.contextPath=/fhir")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class ClaimSubmitTest {

  @LocalServerPort
  private int port;

  @Autowired
  private WebApplicationContext wac;

  private static ResultMatcher cors = MockMvcResultMatchers.header().string("Access-Control-Allow-Origin", "*");
  private static ResultMatcher ok = MockMvcResultMatchers.status().isOk();
  private static ResultMatcher badRequest = MockMvcResultMatchers.status().isBadRequest();

  public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

  /** List of resource IDs that will need to be cleaned up after the tests */
  private static List<String> resourceIds;
  /** Prior Authorization claim fixture */
  private static String completeClaim;
  private static String emptyBundle;
  private static String claimOnly;
  private static String bundleWithOnlyClaim;

  @BeforeClass
  public static void setup() throws IOException {
    App.initializeAppDB();
    resourceIds = new ArrayList<String>();

    // Read in the test fixtures...
    Path modulesFolder = Paths.get("src/test/resources");
    Path fixture = modulesFolder.resolve("bundle-prior-auth.json");
    completeClaim = new String(Files.readAllBytes(fixture));

    fixture = modulesFolder.resolve("bundle-minimal.json");
    emptyBundle = new String(Files.readAllBytes(fixture));

    fixture = modulesFolder.resolve("claim-minimal.json");
    claimOnly = new String(Files.readAllBytes(fixture));

    fixture = modulesFolder.resolve("bundle-with-only-claim.json");
    bundleWithOnlyClaim = new String(Files.readAllBytes(fixture));
  }

  @AfterClass
  public static void cleanup() {
    for (String id : resourceIds) {
      System.out.println("Deleting Resources with ID = " + id);
      App.getDB().delete(Database.BUNDLE, id, "pat013");
      App.getDB().delete(Database.CLAIM, id, "pat013");
      App.getDB().delete(Database.CLAIM_RESPONSE, id, "pat013");
    }
  }

  @Test
  public void completeClaimValidation() {
    Bundle bundle = (Bundle) App.FHIR_CTX.newJsonParser().parseResource(completeClaim);
    Assert.assertNotNull(bundle);
    ValidationResult result = ValidationHelper.validate(bundle);
    Assert.assertTrue(result.isSuccessful());
  }

  @Test
  public void submitCompleteClaim() throws Exception {
    // Test that we can POST /fhir/Claim/$submit
    DefaultMockMvcBuilder builder = MockMvcBuilders.webAppContextSetup(wac);
    MockMvc mockMvc = builder.build();
    MockHttpServletRequestBuilder requestBuilder = MockMvcRequestBuilders.post("/Claim/$submit").content(completeClaim)
        .header("Content-Type", "application/fhir+json").header("Access-Control-Request-Method", "POST")
        .header("Origin", "http://localhost:" + port);

    // Test the response has CORS headers and returned status 200
    MvcResult mvcresult = mockMvc.perform(requestBuilder).andExpect(ok).andExpect(cors).andReturn();

    // Check Location header if it exists...
    String location = mvcresult.getResponse().getHeader("Location");
    if (location != null) {
      int index = location.indexOf("fhir/ClaimResponse/");
      if (index >= 0) {
        resourceIds.add(location.substring(index + 19));
      }
    }

    // Test the response is a JSON Bundle
    String responseBody = mvcresult.getResponse().getContentAsString();
    Bundle bundleResponse = (Bundle) App.FHIR_CTX.newJsonParser().parseResource(responseBody);
    Assert.assertNotNull(bundleResponse);

    // Make sure we clean up afterwards...
    String id = bundleResponse.getId();
    resourceIds.add(id);

    // Test that the database contains the proper entries
    Map<String, Object> constraintMap = new HashMap<String, Object>();
    constraintMap.put("id", id);
    constraintMap.put("patient", "pat013");
    Assert.assertNotNull(App.getDB().read(Database.BUNDLE, constraintMap));
    Assert.assertNotNull(App.getDB().read(Database.CLAIM, constraintMap));
    Assert.assertNotNull(App.getDB().read(Database.CLAIM_RESPONSE, constraintMap));

    // Validate the response.
    ValidationResult result = ValidationHelper.validate(bundleResponse);
    Assert.assertTrue(result.isSuccessful());
  }

  @Test
  public void submitEmptyBundle() throws Exception {
    checkErrors(emptyBundle);
  }

  @Test
  public void submitClaimOnly() throws Exception {
    checkErrors(claimOnly);
  }

  @Test
  public void submitBundleWithOnlyClaim() throws Exception {
    checkErrors(bundleWithOnlyClaim);
  }

  private void checkErrors(String body) throws Exception {
    // Test that we can POST /fhir/Claim/$submit
    DefaultMockMvcBuilder builder = MockMvcBuilders.webAppContextSetup(wac);
    MockMvc mockMvc = builder.build();
    MockHttpServletRequestBuilder requestBuilder = MockMvcRequestBuilders.post("/Claim/$submit").content(body)
        .header("Content-Type", "application/fhir+json").header("Access-Control-Request-Method", "POST")
        .header("Origin", "http://localhost:" + port);

    // Test the response has CORS headers and returned status 400
    MvcResult mvcresult = mockMvc.perform(requestBuilder).andExpect(badRequest).andExpect(cors).andReturn();

    // Test the response is a JSON Operation Outcome
    String responseBody = mvcresult.getResponse().getContentAsString();
    OperationOutcome error = (OperationOutcome) App.FHIR_CTX.newJsonParser().parseResource(responseBody);
    Assert.assertNotNull(error);

    // Validate the response.
    ValidationResult result = ValidationHelper.validate(error);
    Assert.assertTrue(result.isSuccessful());
  }
}
