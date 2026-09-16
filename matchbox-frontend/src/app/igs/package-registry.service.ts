import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

/**
 * The FHIR package registry browsed from the IGs page. It allows CORS requests from any origin.
 */
export const PACKAGE_REGISTRY_URL = 'https://packages2.fhir.org/packages';

/**
 * An entry of the registry catalog, i.e. the latest version of a package.
 */
export interface PackageCatalogEntry {
  name: string;
  version: string;
  fhirVersion: string;
  canonical?: string;
  kind?: string;
  url?: string;
  date?: string;
  description?: string;
}

/**
 * A version of a package, as listed in the NPM-style package document of the registry.
 */
export interface PackageVersion {
  name: string;
  version: string;
  fhirVersion?: string;
  date?: string;
  description?: string;
  url?: string;
}

interface PackageDocument {
  name: string;
  'dist-tags'?: { latest?: string };
  versions?: { [version: string]: PackageVersion };
}

@Injectable({
  providedIn: 'root',
})
export class PackageRegistryService {
  constructor(private readonly http: HttpClient) {}

  /**
   * Searches the registry catalog.
   *
   * @param name a fragment of the package name (the registry matches any part of the name, case-insensitive; wildcards
   *             are not supported).
   * @param fhirVersion the FHIR release (R4, R4B, R5), or an empty string for all.
   * @param since an ISO date (YYYY-MM-DD); only packages published on or after that day are returned. The registry
   *              has no date parameter, so this filter is applied locally.
   * @returns the matching packages, the most recently published first.
   */
  async searchCatalog(name: string, fhirVersion: string, since: string): Promise<PackageCatalogEntry[]> {
    let params = new HttpParams();
    // The registry rejects wildcards, remove them so that 'ch.fhir.*' behaves like 'ch.fhir.'
    const nameFragment = name.replace(/[*%?]/g, '').trim();
    if (nameFragment) {
      params = params.set('name', nameFragment);
    }
    if (fhirVersion) {
      params = params.set('fhirversion', fhirVersion);
    }
    const entries = await firstValueFrom(
      this.http.get<PackageCatalogEntry[]>(`${PACKAGE_REGISTRY_URL}/catalog`, { params })
    );
    return (entries ?? [])
      .filter((entry) => !since || (entry.date ?? '') >= since)
      .sort((a, b) => (b.date ?? '').localeCompare(a.date ?? ''));
  }

  /**
   * Lists all versions of a package, the most recently published first.
   */
  async getVersions(name: string): Promise<{ latest: string | null; versions: PackageVersion[] }> {
    const document = await firstValueFrom(
      this.http.get<PackageDocument>(`${PACKAGE_REGISTRY_URL}/${encodeURIComponent(name)}`)
    );
    const versions = Object.values(document.versions ?? {}).sort((a, b) => (b.date ?? '').localeCompare(a.date ?? ''));
    return { latest: document['dist-tags']?.latest ?? null, versions };
  }
}
