package ch.ahdis.matchbox.validation.itb.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * Test assertion report produced by messaging, processing or validation operations.
 * <p>
 * From the GITB validation service REST API ({@code gitb_vs.json}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TAR {
	/**
	 * The date when the report was produced (date-time).
	 */
	private String date;

	private TestResultType result;

	/**
	 * The report ID.
	 */
	private String id;

	private ValidationOverview overview;

	private ValidationCounters counters;

	/**
	 * The report context, a map whose items a test session can read (e.g. with {@code output="$ctx"}).
	 */
	private AnyContent context;

	/**
	 * The name of the report.
	 */
	private String name;

	/**
	 * The report's included items.
	 */
	private List<ReportItem> items;

	public String getDate() {
		return this.date;
	}

	public TAR setDate(final String date) {
		this.date = date;
		return this;
	}

	public TestResultType getResult() {
		return this.result;
	}

	public TAR setResult(final TestResultType result) {
		this.result = result;
		return this;
	}

	public String getId() {
		return this.id;
	}

	public TAR setId(final String id) {
		this.id = id;
		return this;
	}

	public ValidationOverview getOverview() {
		return this.overview;
	}

	public TAR setOverview(final ValidationOverview overview) {
		this.overview = overview;
		return this;
	}

	public ValidationCounters getCounters() {
		return this.counters;
	}

	public TAR setCounters(final ValidationCounters counters) {
		this.counters = counters;
		return this;
	}

	public AnyContent getContext() {
		return this.context;
	}

	public TAR setContext(final AnyContent context) {
		this.context = context;
		return this;
	}

	public String getName() {
		return this.name;
	}

	public TAR setName(final String name) {
		this.name = name;
		return this;
	}

	public List<ReportItem> getItems() {
		return this.items;
	}

	public TAR setItems(final List<ReportItem> items) {
		this.items = items;
		return this;
	}

	public TAR addItem(final ReportItem item) {
		if (this.items == null) {
			this.items = new ArrayList<>();
		}
		this.items.add(item);
		return this;
	}
}
