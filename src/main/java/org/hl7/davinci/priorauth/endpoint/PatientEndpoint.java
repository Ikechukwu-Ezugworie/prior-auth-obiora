package org.hl7.davinci.priorauth.endpoint;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.hl7.davinci.priorauth.App;
import org.hl7.davinci.priorauth.Audit;
import org.hl7.davinci.priorauth.Audit.AuditEventOutcome;
import org.hl7.davinci.priorauth.Audit.AuditEventType;
import org.hl7.davinci.priorauth.authorization.AuthUtils;
import org.hl7.davinci.priorauth.FhirUtils;
import org.hl7.davinci.priorauth.PALogger;
import org.hl7.davinci.priorauth.Database.Table;
import org.hl7.davinci.priorauth.endpoint.Endpoint.RequestType;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.AuditEvent.AuditEventAction;
import org.hl7.fhir.r4.model.Address;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.Parameters.ParametersParameterComponent;
import org.hl7.fhir.r4.model.Patient.ContactComponent;

import ca.uhn.fhir.parser.IParser;

/**
 * The Patient endpoint to READ, SEARCH for, and DELETE matchted patients.
 */
@CrossOrigin
@RestController
@RequestMapping("/Patient")
public class PatientEndpoint {

  static final Logger logger = PALogger.getLogger();
  static final String REQUIRES_PARAMETERS = "Patient matching Patient/$match Operation requires a Parameters resource containing"
      + " a single Patient resource in parameter field.";
  static final String REQUIRES_PATIENT = "Parameters.parameter must contain a single patient resource as the first element.";
  static final String REQUIRES_MIN_CRITERIA = "The Patient resource provided is not conformant to profile: %s as claimed. "
      + "Please check the profile's constraints and ensure the minimum search fields are provided.";
  static final String MISSING_OR_INVALID_PROFILE = "Patient's profile is missing or not defined in the IG."
      + " Please provide the IDI profile the patient resource conforms to.";
  static final String PROCESS_FAILED = "Unable to process the request properly. Check the log for more details.";
  static final String BASE_PROFILE = "http://hl7.org/fhir/us/identity-matching/StructureDefinition/IDI-Patient";
  static final String LEVEL0_PROFILE = "http://hl7.org/fhir/us/identity-matching/StructureDefinition/IDI-Patient-L0";
  static final String LEVEL1_PROFILE = "http://hl7.org/fhir/us/identity-matching/StructureDefinition/IDI-Patient-L1";
  static final List<String> sortableParams = Arrays.asList("id", "firstname", "lastname");

  // JSON output
  @GetMapping(value = { "", "/{id}" }, produces = { MediaType.APPLICATION_JSON_VALUE, "application/fhir+json" })
  public ResponseEntity<String> readPatientJson(HttpServletRequest request,
      @PathVariable(required = false) String id,
      @RequestParam(name = "identifier", required = false) String patient,
      @RequestParam(name = "sortParam", required = false) String sortParam) {
    Map<String, Object> constraintMap = new HashMap<>();
    if (sortParam != null) {
      if (!sortableParams.contains(sortParam)) {
        return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
      }
    constraintMap.put("sortParam", sortParam);
    }
    constraintMap.put("id", id);
    constraintMap.put("patient", patient);
    return Endpoint.read(Table.PATIENT, constraintMap, request, RequestType.JSON);
  }

  // XML output
  @GetMapping(value = { "", "/{id}" }, produces = { MediaType.APPLICATION_XML_VALUE, "application/fhir+xml" })
  public ResponseEntity<String> readPatientXml(HttpServletRequest request,
      @PathVariable(required = false) String id,
      @RequestParam(name = "identifier", required = false) String patient,
      @RequestParam(name = "sortParam", required = false) String sortParam) {
    Map<String, Object> constraintMap = new HashMap<>();
    if (sortParam != null) {
      if (!sortableParams.contains(sortParam)) {
        String error = "The sortParam parameter must be one of: " + sortableParams.toString();
        logger.info(error);
        logger.fine(error);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
      }
    constraintMap.put("sortParam", sortParam);
    }
    constraintMap.put("id", id);
    constraintMap.put("patient", patient);
    return Endpoint.read(Table.PATIENT, constraintMap, request, RequestType.XML);
  }

  @CrossOrigin
  @DeleteMapping(value = "/{id}", produces = { MediaType.APPLICATION_JSON_VALUE, "application/fhir+json" })
  public ResponseEntity<String> deleteBundle(HttpServletRequest request, @PathVariable String id,
      @RequestParam(name = "identifier", required = false) String patient) {
    return Endpoint.delete(id, patient, Table.PATIENT, request, RequestType.JSON);
  }

  @CrossOrigin
  @DeleteMapping(value = "/{id}", produces = { MediaType.APPLICATION_XML_VALUE, "application/fhir+xml" })
  public ResponseEntity<String> deleteBundleXml(HttpServletRequest request,
      @PathVariable String id, @RequestParam(name = "identifier", required = false) String patient) {
    return Endpoint.delete(id, patient, Table.PATIENT, request, RequestType.XML);
  }

  @PostMapping(value = "/$match", consumes = { MediaType.APPLICATION_JSON_VALUE, "application/fhir+json" })
  public ResponseEntity<String> matchOperationJson(HttpServletRequest request, HttpEntity<String> entity) {
    return matchOperation(entity.getBody(), RequestType.JSON, request);
  }

  @PostMapping(value = "/$match", consumes = { MediaType.APPLICATION_XML_VALUE, "application/fhir+xml" })
  public ResponseEntity<String> matchOperationXml(HttpServletRequest request, HttpEntity<String> entity) {
    return matchOperation(entity.getBody(), RequestType.XML, request);
  }

  /**
   * The matchOperation ($match) function for both json and xml
   * The Digital Identity and Patient Matching extends the HL7
   * FHIR patient $match operation. $match works as follow
   * 1. A trusted party makes a request to Patient/$match
   * -- providing a set of demographic details.
   * 2. The server checks the incoming request and first confirms it
   * is from a trusted client by validating the access token.
   * 3. Next the server checks the incoming Parameters resource in the
   * request body contains a Patient resource in its parameter field and
   * validates the patient resource against its profile (IDI-base, IDI-L1,
   * IDI-L2).
   * 4. Finally the server performed DB search to find possible matches.
   * 
   * @param body        - the body of the post request.
   * @param requestType - the RequestType of the request.
   * @return - Bundle containing a set of possible matching Patient entries
   */
  private ResponseEntity<String> matchOperation(String body, RequestType requestType, HttpServletRequest request) {
    logger.info("POST /Patient/$match fhir+" + requestType.name());
    App.setBaseUrl(Endpoint.getServiceBaseUrl(request));

    // TODO: check it is conforming to UDAP workflow
    if (!AuthUtils.validateAccessToken(request))
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
          .body("{ error: \"Invalid access token. Make sure to use Authorization: Bearer (token)\" }");

    HttpStatus status = HttpStatus.BAD_REQUEST;
    String formattedData = null;
    AuditEventOutcome auditOutcome = AuditEventOutcome.MINOR_FAILURE;

    try {
      IParser parser = requestType == RequestType.JSON ? App.getFhirContext().newJsonParser()
          : App.getFhirContext().newXmlParser();
      IBaseResource resource = parser.parseResource(body);
      logger.info("PatientEndpoint::MatchOperationParsedInput: " + resource.toString());
      // First check if the input is a Parameters resource
      if (resource instanceof Parameters) {
        Parameters parameters = (Parameters) resource;
        // Check if the patient resource is provided in the parameter field of the
        // Parameters resource
        if (parameters.hasParameter() && (!parameters.getParameter().isEmpty())) {

          if (parameters.getParameterFirstRep().getResource() instanceof Patient) {
            Patient patient = (Patient) parameters.getParameterFirstRep().getResource();

            // Validate the minimum search criteria.
            Map<String, String> validation = validateMinimumRequirement(patient);
            if (Boolean.parseBoolean(validation.get("validated"))) {
              status = HttpStatus.ACCEPTED;
              // TODO: once the minimum requirement is validated, define the strategy to
              // identify possible match and scoring.
              // TODO: assign the result of the match query to the `formattedData` variable,
              // which is returned at the end of the method.
              /**
               * compare the given patient resouce fields to each pt resource from the DB
               * Possible logic:
               * select patient resource with at least 2 matching fields??
               * * possible fuzzy match on each field (name, address, DOB, etc)???
               * scoring: weight on each field ??
               * 
               * * * Possible Queries:
               * -- SELECT resource FROM Patient WHERE ppn like || dl like || otherIdentifier
               * like
               * -- SELECT resource FROM Patient WHERE firstName like ... AND lastName like
               * ...
               * -- SELECT resource FROM Patient WHERE firstName like ... AND lastName like
               * ... AND dob like
               * -- SELECT resource FROM Patient WHERE firstName like ... AND lastName like
               * ... AND address like ...
               * -- SELECT resource FROM Patient WHERE firstName like ... AND lastNAME like
               * ... AND phone like ...
               * -- SELECT resource FROM Patient WHERE firstName sounds like ... AND lastName
               * sounds like ..
               * -- SELECT resource FROM Patient WHERE firstName sounds like ... AND lastName
               * sounds like .. AND dob like
               * -- SELECT resource FROM Patient WHERE firstName sounds like ... AND lastName
               * sounds like .. AND phone like
               * -- SELECT resource FROM Patient WHERE firstName sounds like ... AND lastName
               * sounds like .. AND address like
               * 
               * * Determine the percent match for each query and take the avg for the final
               * score???
               */
            } else {
              OperationOutcome error = FhirUtils.buildOutcome(IssueSeverity.ERROR, IssueType.INVALID,
                  validation.get("error"));
              formattedData = FhirUtils.getFormattedData(error, requestType);
              logger.severe(
                  "PatientEndpoint::ValidateMinimumRequirement: Patient resource provided is not conformant to profile as claimed.");
            }

          } else {
            // Patient is required...
            OperationOutcome error = FhirUtils.buildOutcome(IssueSeverity.ERROR, IssueType.INVALID, REQUIRES_PATIENT);
            formattedData = FhirUtils.getFormattedData(error, requestType);
            logger.severe(
                "PatientEndpoint::MatchOperation: Parameters parameter must contain a Patient resource as the first element");
          }

        } else {
          // Parameters resource must have a parameter field...
          OperationOutcome error = FhirUtils.buildOutcome(IssueSeverity.ERROR, IssueType.INVALID,
              "Missing Parameters.parmeter");
          formattedData = FhirUtils.getFormattedData(error, requestType);
          logger.severe("PatientEndpoint::MatchOperation: Parameters parameter field is missing");
        }
      } else {
        // Input should be a Parameters resource...
        OperationOutcome error = FhirUtils.buildOutcome(IssueSeverity.ERROR, IssueType.INVALID, REQUIRES_PARAMETERS);
        formattedData = FhirUtils.getFormattedData(error, requestType);
        logger.severe("PatientEndpoint::MatchOperation:Body is not a Parameters resource");
      }
    } catch (Exception e) {
      // Spectacular faillure of the match request
      OperationOutcome error = FhirUtils.buildOutcome(IssueSeverity.FATAL, IssueType.STRUCTURE,
          e.getMessage());
      formattedData = FhirUtils.getFormattedData(error, requestType);
      auditOutcome = AuditEventOutcome.SERIOUS_FAILURE;
    }
    Audit.createAuditEvent(AuditEventType.REST, AuditEventAction.E, auditOutcome, null, request,
        "POST /Patient/$match");
    MediaType contentType = requestType == RequestType.JSON ? MediaType.APPLICATION_JSON : MediaType.APPLICATION_XML;
    String fhirContentType = requestType == RequestType.JSON ? "application/fhir+json" : "application/xml+json";
    return ResponseEntity.status(status).contentType(contentType)
        .header(HttpHeaders.CONTENT_TYPE, fhirContentType + "; charset=utf-8")
        .header(HttpHeaders.LOCATION,
            App.getBaseUrl() + "/Patient")
        .body(formattedData);
  }

  /**
   * Validates the patient resource provided in the Patient/$match request meet
   * the minimum requirement of the profile it claims to be conformant to.
   * * Requirements for Base Level:
   * -- Patient path: identifier.exists() or telecom.exists() or
   * (name.family.exists() and name.given.exists())
   * or (address.line.exists() and address.city.exists()) or birthDate.exists()
   * -- Patient.contact path: name.exists() or telecom.exists() or
   * address.exists() or organization.exists()
   * 
   * * Requirements for Level 0 weighted:
   * -- Patient Path: (((identifier.type.coding.exists(code = 'PPN') and
   * identifier.value.exists()).toInteger()*10)
   * + ((identifier.type.coding.exists(code = 'DL') and
   * identifier.value.exists()).toInteger()*10)
   * + (((address.exists(use = 'home') and address.line.exists() and
   * address.city.exists())
   * or (identifier.type.coding.exists(code != 'PPN' and code != 'DL'))
   * or ((telecom.exists(system = 'email') and telecom.value.exists())
   * or (telecom.exists(system = 'phone') and telecom.value.exists())) or
   * (photo.exists())).toInteger() * 4)
   * + ((name.family.exists() and name.given.exists()).toInteger()*4) +
   * (birthDate.exists().toInteger()*2)) >= 10
   * 
   * -- Patient.contact path: name.exists() or telecom.exists() or
   * address.exists() or organization.exists()
   * 
   * * Requirements for Level 1 weighted:
   * -- Patient Path: (((identifier.type.coding.exists(code = 'PPN') and
   * identifier.value.exists()).toInteger()*10)
   * + ((identifier.type.coding.exists(code = 'DL') and
   * identifier.value.exists()).toInteger()*10)
   * + (((address.exists(use = 'home') and address.line.exists() and
   * address.city.exists())
   * or (identifier.type.coding.exists(code != 'PPN' and code != 'DL'))
   * or ((telecom.exists(system = 'email') and telecom.value.exists())
   * or (telecom.exists(system = 'phone') and telecom.value.exists())) or
   * (photo.exists())).toInteger() * 4)
   * + ((name.family.exists() and name.given.exists()).toInteger()*4) +
   * (birthDate.exists().toInteger()*2)) >= 20
   * 
   * -- Patient.contact path: name.exists() or telecom.exists() or
   * address.exists() or organization.exists()
   * 
   * @param patient - the patient resource contained in the Parameters resource
   *                provided in the post request.
   * @return - a hashMap containing a String form of boolean value in the
   *         `validated` key
   *         indicating whether the constrains are validated,
   *         and a String value in the `error` key describing the reason for
   *         invalidating.
   */
  private Map<String, String> validateMinimumRequirement(Patient patient) {
    Boolean validated = false;
    Map<String, String> validationMap = new HashMap<>();
    String profile = FhirUtils.getProfileMetaFromPatient(patient);
    if (profile == null || !profile.matches(BASE_PROFILE + "|" + LEVEL0_PROFILE + "|" + LEVEL1_PROFILE)) {
      validationMap.put("validated", String.valueOf(validated));
      validationMap.put("error", MISSING_OR_INVALID_PROFILE);
      return validationMap;
    }

    Address patientAddress = patient.hasAddress() && !patient.getAddress().isEmpty()
        ? patient.getAddress().stream().filter(address -> (address.hasLine() && address.hasCity())).findFirst()
            .orElse(null)
        : null;
    Map<String, Object> fullName = FhirUtils.getPatientFirstAndLastName(patient);
    Boolean hasFullName = (fullName.get("firstName") != null) && (fullName.get("lastName") != null);
    ContactComponent patientContact = patient.hasContact() ? patient.getContactFirstRep() : null;
    Boolean hasContact = patientContact != null && (patientContact.hasName() || patientContact.hasTelecom()
        || patientContact.hasAddress() || patientContact.hasOrganization());
    int weight = totalWeigh(patient);
    if (profile.equals(LEVEL0_PROFILE)) {
      validated = hasContact && weight >= 10;
    } else if (profile.equals(LEVEL1_PROFILE)) {
      validated = hasContact && weight >= 20;
    } else {
      validated = hasContact && (patient.hasIdentifier() || patient.hasTelecom() || hasFullName
          || patientAddress != null || patient.hasBirthDate());
    }
    String error = validated ? "" : String.format(REQUIRES_MIN_CRITERIA, profile);
    validationMap.put("validated", String.valueOf(validated));
    validationMap.put("error", error);
    return validationMap;
  }

  /**
   * Internal method to calculate the weight of the $match operation search
   * criteria
   * 
   * @param patient - the provided patient resource from the post query
   * @return the total weight of the search fields
   */
  private int totalWeigh(Patient patient) {
    Map<String, Object> fullName = FhirUtils.getPatientFirstAndLastName(patient);
    Boolean hasFullName = (fullName.get("firstName") != null) && (fullName.get("lastName") != null);

    int ppnWeight = FhirUtils.getPasportNumFromPatient(patient) != null ? 10 : 0;
    int dlWeight = FhirUtils.getDLNumFromPatient(patient) != null ? 10 : 0;
    int addressOrTelOrOtherIdWeight = (FhirUtils.getPatientHomeAddress(patient) != null
        || FhirUtils.getOtherIdentifierFromPatient(patient) != null
        || FhirUtils.getPatientPhone(patient) != null || FhirUtils.getPatientEmail(patient) != null
        || patient.hasPhoto()) ? 4 : 0;
    int nameWeight = hasFullName ? 4 : 0;
    int birthDateWeight = patient.hasBirthDate() ? 2 : 0;

    return ppnWeight + dlWeight + addressOrTelOrOtherIdWeight + nameWeight + birthDateWeight;
  }

}
