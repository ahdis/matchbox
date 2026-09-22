package ch.ahdis.matchbox.validation.itb.models;

/**
 * Overall result values used in reports.
 * <p>
 * From the GITB validation service REST API ({@code gitb_vs.json}).
 */
public enum TestResultType {
	SUCCESS,
	FAILURE,
	WARNING,
	UNDEFINED
}
