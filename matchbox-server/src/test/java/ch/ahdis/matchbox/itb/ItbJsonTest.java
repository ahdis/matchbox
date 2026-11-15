package ch.ahdis.matchbox.itb;

import ch.ahdis.matchbox.validation.itb.ItbJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.gitb.model.core.AnyContent;
import com.gitb.model.core.ValueEmbeddingEnumeration;
import com.gitb.model.tr.ReportItem;
import com.gitb.model.tr.SeverityLevel;
import com.gitb.model.tr.TAR;
import com.gitb.model.tr.TestResultType;
import com.gitb.model.tr.ValidationCounters;
import com.gitb.model.tr.ValidationOverview;
import com.gitb.model.vs.ValidateRequest;
import com.gitb.model.vs.ValidationResponse;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JSON of the GITB types has to be the one of the REST API of the ITB services ({@code gitb_vs.json}); the types
 * of {@code gitb-types-model} have no JSON annotations.
 */
class ItbJsonTest {

	@Test
	void reportIsWrittenAsInTheContract() throws Exception {
		final var tar = TAR.builder()
			.withId("id-1")
			.withDate(ZonedDateTime.of(2026, 11, 15, 10, 20, 30, 0, ZoneOffset.ofHours(1)))
			.withResult(TestResultType.FAILURE)
			.withCounters(ValidationCounters.builder().withNrOfErrors(1).withNrOfWarnings(0).withNrOfAssertions(2).build())
			.withOverview(ValidationOverview.builder().withProfileID("http://example.org/p|1.0.0").build())
			.withItem(ReportItem.builder().withLevel(SeverityLevel.ERROR).withAssertionID("a-1").build())
			.withContext(AnyContent.builder()
								 .withType("map")
								 .withItem(AnyContent.builder()
												  .withName("errorCount")
												  .withValue("1")
												  .withEmbeddingMethod(ValueEmbeddingEnumeration.STRING)
												  .withForContext(true)
												  .withForDisplay(false)
												  .build())
								 .build())
			.build();

		final JsonNode report = ItbJson.mapper()
			.readTree(ItbJson.mapper().writeValueAsString(ValidationResponse.builder().withReport(tar).build()))
			.get("report");

		assertEquals("2026-11-15T10:20:30+01:00", report.get("date").asText());
		assertEquals("FAILURE", report.get("result").asText());
		assertEquals(1, report.get("counters").get("nrOfErrors").asInt());
		assertEquals(2, report.get("counters").get("nrOfAssertions").asInt());
		assertEquals("http://example.org/p|1.0.0", report.get("overview").get("profileID").asText());
		assertFalse(report.get("overview").has("note"), "null values are not written");
		assertEquals("ERROR", report.get("items").get(0).get("level").asText());
		assertEquals("a-1", report.get("items").get(0).get("assertionID").asText());

		final JsonNode context = report.get("context");
		assertEquals("map", context.get("type").asText());
		final JsonNode errorCount = context.get("item").get(0);
		assertEquals("STRING", errorCount.get("embeddingMethod").asText());
		assertTrue(errorCount.get("forContext").asBoolean());
		assertFalse(errorCount.get("forDisplay").asBoolean());
		assertFalse(errorCount.has("item"), "a value without children has no item array");
	}

	@Test
	void requestIsReadLeniently() throws Exception {
		final ValidateRequest request = ItbJson.mapper().readValue("""
			{
			  "sessionId": "s-1",
			  "unknownProperty": true,
			  "input": [
			    {"name": "contentToValidate", "value": "e30=", "embeddingMethod": "BASE_64", "newProperty": 1},
			    {"name": "a", "value": "x", "embeddingMethod": "base64"},
			    {"name": "b", "value": "y"},
			    {"name": "c", "type": "list", "item": [{"value": "1"}, {"value": "2"}]}
			  ]
			}""", ValidateRequest.class);

		assertEquals("s-1", request.getSessionId());
		assertEquals(4, request.getInput().size());
		assertEquals(ValueEmbeddingEnumeration.BASE_64, request.getInput().get(0).getEmbeddingMethod());
		assertEquals(ValueEmbeddingEnumeration.BASE_64, request.getInput().get(1).getEmbeddingMethod());
		assertNull(request.getInput().get(2).getEmbeddingMethod());
		assertEquals(2, request.getInput().get(3).getItem().size());

		assertThrows(Exception.class, () -> ItbJson.mapper().readValue(
			"{\"input\": [{\"name\": \"a\", \"embeddingMethod\": \"FTP\"}]}", ValidateRequest.class));
	}

	@Test
	void reportDateKeepsItsOffset() throws Exception {
		final TAR tar = ItbJson.mapper().readValue("{\"date\": \"2026-11-15T10:20:30+01:00\"}", TAR.class);
		assertEquals(ZoneOffset.ofHours(1), tar.getDate().getOffset());
	}
}
