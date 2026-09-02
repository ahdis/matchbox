import {IssueSeverity, OperationResult} from '../util/operation-result';
import {ValidationParameter} from "./validation-parameter";
import { FhirResource, parseFhirResource } from '../util/fhir-resource-parser';

export class ValidationEntry {
  readonly filename: string; // "package/package.json",
  readonly content: string;
  readonly resource: FhirResource;
  resourceType: string;
  format: FhirFormat;
  resourceId: string | null;
  result: OperationResult | undefined;
  readonly extractedProfiles: string[] = [];
  validationProfile: string | null;
  ig?: string;
  readonly date: Date;
  readonly validationParameters: ValidationParameter[] = [];
  public loading: boolean = false;

  constructor(filename: string,
              resource: string,
              mimetype: string | null,
              settings: ValidationParameter[] = [],
              validationProfile: string | null = null) {
    this.filename = filename;
    this.content = resource;
    this.validationParameters = settings;

    if (mimetype) {
      switch (mimetype) {
        case 'application/fhir+json':
        case 'application/json':
        case 'json':
          this.format = FhirFormat.JSON;
          break;
        case 'application/fhir+xml':
        case 'application/xml':
        case 'text/xml':
        case 'xml':
          this.format = FhirFormat.XML;
          break;
        default:
          throw new Error(`Unsupported mimetype ${mimetype}`);
      }
    } else {
      if (filename.endsWith('.json')) {
        this.format = FhirFormat.JSON;
      } else {
        this.format = FhirFormat.XML;
      }
    }

    this.date = new Date();
    this.validationProfile = validationProfile;

    const parsed = parseFhirResource(filename, resource);
    if (!parsed) {
      throw new Error(`Failed to parse resource from file ${filename}`);
    }
    this.resource = parsed;
    this.resourceType = parsed.resourceType;
    this.resourceId = parsed.id;
    this.extractedProfiles.push(...parsed.profiles);
  }

  public get mediaType() {
    return (this.format == FhirFormat.JSON) ? 'application/fhir+json' : 'application/fhir+xml';
  }

  getErrors(): number | undefined {
    if (this.result) {
      return this.result.issues.filter((issue) => issue.severity === IssueSeverity.Error || issue.severity === IssueSeverity.Fatal)
        .length;
    }
    return undefined;
  }

  getWarnings(): number | undefined {
    if (this.result) {
      return this.result.issues.filter((issue) => issue.severity === IssueSeverity.Warning).length;
    }
    return undefined;
  }

  getInfos(): number | undefined {
    if (this.result) {
      return this.result.issues.filter((issue) => issue.severity === IssueSeverity.Information).length;
    }
    return undefined;
  }

  setOperationOutcome(operationOutcome: fhir.r4.OperationOutcome): void {
    this.result = OperationResult.fromOperationOutcome(operationOutcome);
  }
}

export enum FhirFormat { JSON, XML}
