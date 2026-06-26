import FhirClient from 'fhir-kit-client';
import CapabilityStatement = fhir.r4.CapabilityStatement;
import Resource = fhir.r4.Resource;
import OperationDefinition = fhir.r4.OperationDefinition;
import Bundle = fhir.r4.Bundle;
import OperationOutcome = fhir.r4.OperationOutcome;
import Parameters = fhir.r4.Parameters;
import { ValidationEntry } from '../validate/validation-entry';

/**
 * A wrapper for the FHIR client that provides a simpler API for our needs, with the right types.
 */
export class FhirClientWrapper {
  private readonly client: FhirClient;

  constructor(readonly baseUrl: string) {
    this.client = new FhirClient({
      baseUrl: baseUrl,
    });
  }

  capabilityStatement(): Promise<CapabilityStatement> {
    return this.client.capabilityStatement() as Promise<CapabilityStatement>;
  }

  search(params: any): Promise<Bundle> {
    return this.client.search(params) as Promise<Bundle>;
  }

  create(params: any): Promise<OperationOutcome> {
    return this.client.create(params) as Promise<OperationOutcome>;
  }

  update(params: any): Promise<OperationOutcome> {
    return this.client.update(params) as Promise<OperationOutcome>;
  }

  delete(params: any): Promise<OperationOutcome> {
    return this.client.delete(params) as Promise<OperationOutcome>;
  }

  transformFromUrl(structureMapUrl: string, resource: Resource): Promise<Resource> {
    return this.client.operation({
      name: 'transform?source=' + encodeURIComponent(structureMapUrl),
      resourceType: 'StructureMap',
      input: resource,
    }) as Promise<Resource>;
  }

  transformFromParameters(parameters: Parameters): Promise<Resource> {
    return this.client.operation({
      name: 'transform',
      resourceType: 'StructureMap',
      input: parameters,
      options: {
        headers: {
          'content-type': 'application/fhir+json',
        },
      },
    }) as Promise<Resource>;
  }

  readOperationDefinition(id: string): Promise<OperationDefinition> {
    return this.client.read({
      resourceType: 'OperationDefinition',
      id: id,
    }) as Promise<OperationDefinition>;
  }

  nextPage(bundle: Bundle): Promise<Bundle> {
    return this.client.nextPage({ bundle }) as Promise<Bundle>;
  }

  prevPage(bundle: Bundle): Promise<Bundle> {
    return this.client.prevPage({ bundle }) as Promise<Bundle>;
  }

  listStructureMaps(): Promise<Bundle> {
    return this.client.operation({
      name: 'list',
      resourceType: 'StructureMap',
      method: 'GET',
    }) as Promise<Bundle>;
  }

  validate(entry: ValidationEntry): Promise<OperationOutcome> {
    const searchParams = new URLSearchParams();
    searchParams.set('profile', entry.validationProfile!!);
    if (entry.ig) {
      searchParams.set('ig', entry.ig);
    }
    // Validation options
    for (const param of entry.validationParameters) {
      searchParams.append(param.name, param.value);
    }
    return this.client.operation({
      name: 'validate?' + searchParams.toString(),
      resourceType: undefined,
      input: entry.resource,
      options: {
        headers: {
          accept: 'application/fhir+json',
          'content-type': entry.mimetype,
        },
      },
    }) as Promise<OperationOutcome>;
  }
}
