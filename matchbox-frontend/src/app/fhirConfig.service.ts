import { Injectable } from '@angular/core';
import FhirClient from 'fhir-kit-client';
import { environment } from '../environments/environment';

@Injectable({
  providedIn: 'root',
})
export class FhirConfigService {
  constructor() {}

  public changeFhirMicroService(server: string) {
    localStorage.setItem('fhirMicroServer', server);
  }

  getFhirMicroService(): string {
    return localStorage.getItem('fhirMicroServer') ?? environment.fhirServerUrl();
  }

  getFhirClient() {
    return new FhirClient({ baseUrl: this.getFhirMicroService() });
  }
}
