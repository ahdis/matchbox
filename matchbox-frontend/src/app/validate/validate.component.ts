import {AfterViewInit, ChangeDetectorRef, Component} from '@angular/core';
import {FhirConfigService} from '../fhirConfig.service';
import FhirClient from 'fhir-kit-client';
import pako from 'pako';
import untar from 'js-untar';
import {IDroppedBlob} from '../upload/upload.component';
import ace from 'ace-builds';
import {ValidationEntry} from './validation-entry';
import {ValidationParameter, ValidationParameterDefinition} from './validation-parameter';
import {ITarEntry} from './tar-entry';
import {Issue, OperationResult} from '../util/operation-result';
import {FormControl, Validators} from '@angular/forms';
import {StructureDefinition} from './structure-definition';
import {ToastrService} from 'ngx-toastr';
import {ValidationCodeEditor} from "./validation-code-editor";
import {Base64} from 'js-base64';

const INDENT_SPACES = 2;

@Component({
  selector: 'app-validate',
  templateUrl: './validate.component.html',
  styleUrls: ['./validate.component.scss'],
  standalone: false
})
export class ValidateComponent implements AfterViewInit {
  readonly AUTO_IG_SELECTION = 'AUTOMATIC';
  readonly CodeEditorContent = CodeEditorContent;
  readonly Array = Array;

  // Validation history
  validationEntries: ValidationEntry[] = [];
  selectedEntry: ValidationEntry | null = null;

  // About the server
  client: FhirClient;
  installedIgs: Set<string> = new Set();
  supportedProfiles: Map<string, StructureDefinition> = new Map();
  validatorSettings: Map<string, ValidationParameterDefinition> = new Map();

  // The input form
  filteredProfiles: Set<StructureDefinition> = new Set();
  profileFilter: string = '';
  selectedIg: string = this.AUTO_IG_SELECTION;
  selectedProfile: string;
  profileControl: FormControl = new FormControl<string>(null, Validators.required);
  profileLocked: boolean = false;

  // Code editor
  editor: ValidationCodeEditor;
  editorContent: CodeEditorContent = CodeEditorContent.RESOURCE_CONTENT;

  // DOM
  showSettings: boolean = false;
  currentResource: UploadedFile | null = null;

  showAIAnalyzeButton: boolean = false;

  package: ArrayBuffer;

  constructor(
    data: FhirConfigService,
    private cd: ChangeDetectorRef,
    private toastr: ToastrService,
  ) {
    this.client = data.getFhirClient();

    const validateOperationDefinitionPromise = this.client
      .read({resourceType: 'OperationDefinition', id: '-s-validate'});

    const implementationGuidesPromise = this.client
      .search({
        resourceType: 'ImplementationGuide',
        searchParams: {
          _sort: 'title',
          _count: 1000, // Load all IGs
        },
      });

    // Wait for the two requests to complete
    Promise.all([validateOperationDefinitionPromise, implementationGuidesPromise])
      .then((values: [fhir.r4.OperationDefinition, fhir.r4.Bundle]) => {
        // Read the server -s-validate OperationDefinition.
        // This will allow us to create the list of supported (installed) profiles, and supported validation parameters.
        this.analyzeValidateOperationDefinition(values[0]);

        // Read the list of installed ImplementationGuides
        values[1].entry
          ?.map((entry: fhir.r4.BundleEntry) => entry.resource as fhir.r4.ImplementationGuide)
          .map((ig: fhir.r4.ImplementationGuide) => `${ig.packageId}#${ig.version}`)
          .sort()
          .forEach((ig) => this.installedIgs.add(ig));
      })
      .catch((error) => {
        this.showErrorToast('Network error', error.message);
        console.error(error);
      });
  }

  ngAfterViewInit() {
    // Initializes the code editor, after the DOM is ready
    this.editor = new ValidationCodeEditor(ace.edit('editor'), INDENT_SPACES);

    // Check for query string parameters in the current URL.
    // They may contain a validation request.
    // This call is placed here because it depends on the `editor` instance being initialized above.
    this.analyzeUrlForValidation().then();
  }

  /**
   * Loads a selected/dropped file in the file selector in Matchbox.
   * @param droppedBlob the selected/dropped file.
   */
  onFileSelected(droppedBlob: IDroppedBlob): void {
    if (droppedBlob.name.endsWith('.tgz')) {
      // Load an IG package
      try {
        this.validateExamplesInPackage(droppedBlob.blob);
      } catch (error) {
        this.showErrorToast('Unexpected error', error.message);
        console.error(error);
      }
      return;
    }

    // We assume that the file is a FHIR resource
    try {
      this.selectedIg = this.AUTO_IG_SELECTION;
      const reader = new FileReader();
      reader.readAsText(droppedBlob.blob);
      reader.onload = () => {
        // need to run CD since file load runs outside of zone
        this.cd.markForCheck();
        this.validateResource(droppedBlob.blob.name, <string>reader.result, droppedBlob.contentType, !this.profileLocked);
      };
    } catch (error) {
      this.showErrorToast('Unexpected error', error.message);
      console.error(error);
    }
  }

  validateResource(filename: string,
                   content: string,
                   contentType: string,
                   selectBestProfile: boolean): void {
    let entry: ValidationEntry;
    try {
      // Try to parse the resource to extract information
      entry = new ValidationEntry(filename, content, contentType, this.getCurrentValidationSettings());
      this.currentResource = new UploadedFile(
        filename,
        contentType,
        content,
        entry.resourceType
      );
      if (selectBestProfile) {
        var profileSet = false;
        for (const profile of entry.extractedProfiles) {
          if (this.supportedProfiles.has(profile)) {
            this.selectedProfile = profile;
            profileSet = true;
            break;
          }
        }
        if (!profileSet) {
          this.selectedProfile = 'http://hl7.org/fhir/StructureDefinition/' + entry.resourceType;
        }
      }
      entry.validationProfile = this.selectedProfile;

      if (entry.validationProfile) {
        this.validationEntries.unshift(entry);
        this.show(entry);
        this.runValidation(entry);
      } else {
        this.showWarnToast('No profile selected', 'Please select a profile for validation');
      }

    } catch (error) {
      this.showErrorToast('Error parsing the file', error.message);
      console.error(error);
      if (entry) {
        entry.result = OperationResult.fromMatchboxError(
          'Error while processing the resource for' + ' validation: ' + error.message
        );
      }
      return;
    }
  }

  /**
   * Analyzes a package file and runs validation for all examples, with the right IG set.
   * @param file the package file to load.
   */
  validateExamplesInPackage(file: File): void {
    this.selectedProfile = null;
    this.selectedIg = this.AUTO_IG_SELECTION;
    const reader = new FileReader();
    reader.readAsArrayBuffer(file);
    reader.onload = () => {
      this.package = <ArrayBuffer>reader.result;
      // need to run CD since file load runs outside of zone
      this.cd.markForCheck();
      if (this.package != null) {
        const result = pako.inflate(new Uint8Array(this.package));
        const dataSource = new Array<ValidationEntry>();
        const pointer = this;
        untar(result.buffer).then(
          (_) => {
            // onSuccess
            dataSource.forEach((entry) => {
              pointer.validationEntries.unshift(entry);
              pointer.runValidation(entry);
            });
          },
          (err) => {
            // onError
            this.showErrorToast('Unexpected error', err);
            console.error(err);
          },
          (extractedFile: ITarEntry) => {
            // onProgress
            if (extractedFile.name?.indexOf('package.json') >= 0) {
            }
            if (extractedFile.name?.indexOf('example') >= 0 && extractedFile.name?.indexOf('.index.json') == -1) {
              let name = extractedFile.name;
              if (name.startsWith('package/example/')) {
                name = name.substring('package/example/'.length);
              }
              if (name.startsWith('example/')) {
                name = name.substring('example/'.length);
              }
              let decoder = new TextDecoder('utf-8');
              // maybe better add ig as a parameter, we assume now that ig version is equal to canonical version
              let entry = new ValidationEntry(name, decoder.decode(extractedFile.buffer), 'application/fhir+json', this.getCurrentValidationSettings());
              dataSource.push(entry);
            }
          }
        );
      }
    };
  }

  /**
   * Clear all history of validation.
   */
  clearAllEntries() {
    this.selectedProfile = null;
    this.selectedIg = this.AUTO_IG_SELECTION;
    this.show(undefined);
    this.validationEntries.splice(0, this.validationEntries.length);
  }

  /**
   * Starts the actual validation of an entry.
   * @param entry the entry to validate.
   */
  runValidation(entry: ValidationEntry) {
    if (this.selectedProfile != null && !this.profileLocked) {
      if (!entry.extractedProfiles.includes(this.selectedProfile)) {
        entry.extractedProfiles.push(this.selectedProfile);
      }
      entry.validationProfile = this.selectedProfile;
    }

    if (this.selectedIg != this.AUTO_IG_SELECTION) {
      if (this.selectedIg.endsWith(' (last)')) {
        entry.ig = this.selectedIg.substring(0, this.selectedIg.length - 7);
      } else {
        entry.ig = this.selectedIg;
      }
    }

    if (!entry.validationProfile) {
      this.showErrorToast('Validation failed', 'No profile was selected');
      console.error("No profile selected, won't run validation");
      return;
    }

    const searchParams = new URLSearchParams();
    searchParams.set('profile', entry.validationProfile);
    if (entry.ig) {
      searchParams.set('ig', entry.ig);
    }

    // Validation options
    for (const param of entry.validationParameters) {
      searchParams.append(param.name, param.value);
    }
    entry.loading = true;
    this.client
      .operation({
        name: 'validate?' + searchParams.toString(),
        resourceType: undefined,
        input: entry.resource,
        options: {
          headers: {
            accept: 'application/fhir+json',
            'content-type': entry.mimetype,
          },
        },
      })
      .then((response) => {
        // Got a response that should be an OperationOutcome
        entry.loading = false;
        entry.setOperationOutcome(response);
        if (entry === this.selectedEntry) {
          this.editor.updateCodeEditorContent(this.selectedEntry, this.editorContent);
        }
      })
      .catch((error) => {
        console.error(error);
        entry.loading = false;
        if (error?.response?.data?.resourceType === 'OperationOutcome') {
          // Got an OperationOutcome, probably with a 500-error code
          entry.setOperationOutcome(error?.response?.data);
          if (entry === this.selectedEntry) {
            this.editor.updateCodeEditorContent(this.selectedEntry, this.editorContent);
          }
        } else if ('message' in error) {
          // Got an error message
          this.showErrorToast('Unexpected error', error.message);
          entry.result = OperationResult.fromMatchboxError(
            'Error while sending the validation request: ' + error.message
          );
          console.error(error);
        } else {
          // Got nothing useful, it seems
          this.showErrorToast('Unknown error', 'Unknown error while sending the validation request');
          entry.result = OperationResult.fromMatchboxError(
            'Unknown error while sending the validation request'
          );
          console.error(error);
        }
      });
  }

  /**
   * Select a validation entry to show in the detail pane.
   * @param entry the validation entry to show, or null to deselect the current one.
   */
  show(entry: ValidationEntry | null) {
    this.selectedEntry = entry;
    this.editor.updateCodeEditorContent(this.selectedEntry, this.editorContent);

    if (entry != null) {
      // Set the resource as currently selected in the form, to facilitate re-validation with a different profile/IG
      this.currentResource = new UploadedFile(entry.filename, entry.mimetype, entry.resource, entry.resourceType);
    }
  }

  /**
   * Remove an entry from the history list.
   * @param entry the entry to remove.
   */
  removeEntryFromHistory(entry: ValidationEntry) {
    if (entry === this.selectedEntry) {
      this.show(null);
    }
    const index = this.validationEntries.indexOf(entry);
    this.validationEntries.splice(index, 1); //remove element from array
  }

  /**
   * Event handler for the click on the "validation" button
   */
  onValidationButtonClick() {
    let entry = new ValidationEntry(
      this.currentResource.filename,
      this.currentResource.content,
      this.currentResource.contentType,
      this.getCurrentValidationSettings(),
      this.selectedProfile
    );
    if (this.selectedIg != this.AUTO_IG_SELECTION) {
      entry.ig = this.selectedIg;
    }
    this.validationEntries.unshift(entry);
    this.show(entry);
    this.runValidation(entry);
  }

  onAiAnalyzeButtonClick() {
    let aiAnalyzeSetting = {name: "analyzeOutcomeWithAI", value: "true"};
    let settings = this.getCurrentValidationSettings()
    settings.push(aiAnalyzeSetting);
    let entry = new ValidationEntry(
      this.currentResource.filename,
      this.currentResource.content,
      this.currentResource.contentType,
      settings,
      this.selectedProfile
    );
    if (this.selectedIg != this.AUTO_IG_SELECTION) {
      entry.ig = this.selectedIg;
    }
    this.validationEntries.unshift(entry);
    this.show(entry);
    this.runValidation(entry);
  }

  /**
   * Toggle the display of the settings pane.
   */
  toggleSettings() {
    this.showSettings = !this.showSettings;
  }

  /**
   * Scrolls the code editor to the location of an issue.
   * @param issue the FHIR Issue from an OperationOutcome.
   */
  scrollToIssueLocation(issue: Issue): void {
    if (issue.line && this.editorContent == CodeEditorContent.RESOURCE_CONTENT) {
      // Scroll to the clicked issue, but only if the issue has a location and the resource is shown in the code editor
      // (scrolling in the OperationOutcome would be nonsense).
      this.editor.scrollToIssueLocation(issue);
    }
  }

  /**
   * Updates the list of profiles that are shown in the profile select dropdown by selecting those who contain the
   * search term either in their title or their canonical.
   */
  updateProfileFilter() {
    const searchTerm = this.profileFilter.toLowerCase();
    this.filteredProfiles = new Set<StructureDefinition>(
      [...this.supportedProfiles.values()]
        .filter((sd) => {
          return (
            sd.title.toLocaleLowerCase().includes(searchTerm) || sd.canonical.toLocaleLowerCase().includes(searchTerm)
          );
        })
        .values()
    );
  }

  getDirectLink(entry: ValidationEntry): string {
    const url = new URL(document.location.href);
    // Remove all search params
    url.searchParams.forEach((name: string) => {
      url.searchParams.delete(name);
    });

    const hashParams = new URLSearchParams();
    hashParams.set('resource', Base64.encodeURI(entry.resource));
    hashParams.set('profile', entry.validationProfile);
    if (entry.ig) {
      hashParams.set('ig', entry.ig);
    }

    for (const param of entry.validationParameters) {
      hashParams.set(param.name, param.value);
    }

    url.hash = hashParams.toString();
    return url.toString();
  }

  copyDirectLink(event: MouseEvent, entry: ValidationEntry) {
    if ('clipboard' in navigator) {
      event.preventDefault();
      const url = this.getDirectLink(entry);
      navigator.clipboard.writeText(url).then(() => {
      });
    }
  }

  getExtensionStringValue(element: fhir.r4.Element, url: string): string {
    return this.getExtension(element, url)?.valueString ?? '';
  }

  getExtensionBoolValue(element: fhir.r4.Element, url: string): boolean {
    return this.getExtension(element, url)?.valueBoolean ?? false;
  }

  getExtension(element: fhir.r4.Element, url: string): fhir.r4.Extension {
    for (let i = 0; i < element.extension.length; i++) {
      if (element.extension[i].url === url) {
        return element.extension[i];
      }
    }
    return null;
  }

  changeCodeEditorContent(newContent: CodeEditorContent): void {
    if (this.editorContent === newContent) {
      return;
    }
    this.editorContent = newContent;
    this.editor.updateCodeEditorContent(this.selectedEntry, this.editorContent);
  }

  private getCurrentValidationSettings(): ValidationParameter[] {
    const parameters: ValidationParameter[] = [];
    for (const [_, setting] of this.validatorSettings) {
      if (setting.formControl.value != null && setting.formControl.value.toString().length > 0) {
        const isTextarea = setting.param.type === 'string' && setting.param.max === '*';
        if (isTextarea) {
           const lines = setting.formControl.value.toString().split('\n');
          // Filter out empty lines
          const nonEmptyLines = lines.filter(line => line.trim().length > 0);
          // Add each non-empty line as a separate parameter with the same name
          for (const line of nonEmptyLines) {
            parameters.push(new ValidationParameter(setting.param.name, line.trim()));
          }          
        } else {
          parameters.push(new ValidationParameter(setting.param.name, setting.formControl.value.toString()));
        }
      }
    }
    return parameters;
  }

  /**
   * Show an error toast message.
   * @param title the toast title.
   * @param message the toast message.
   * @private
   */
  private showErrorToast(title: string, message: string) {
    this.toastr.error(message, title, {
      closeButton: true,
      timeOut: 5000,
    });
  }

  /**
   * Show a warning toast message.
   * @param title the toast title.
   * @param message the toast message.
   * @private
   */
  private showWarnToast(title: string, message: string) {
    this.toastr.warning(message, title, {
      closeButton: true,
      timeOut: 5000,
    });
  }

  /**
   * Extracts supported validation parameters from the -s-validate OperationDefinition.
   * @param od the -s-validate OperationDefinition.
   * @private
   */
  private analyzeValidateOperationDefinition(od: fhir.r4.OperationDefinition): void {
    od.parameter?.forEach((parameter: fhir.r4.OperationDefinitionParameter) => {
      if (parameter.name == 'profile') {
        parameter._targetProfile?.forEach((item) => {
          const sd = new StructureDefinition();
          sd.canonical = this.getExtensionStringValue(item, 'sd-canonical');
          sd.title = this.getExtensionStringValue(item, 'sd-title');
          sd.igId = this.getExtensionStringValue(item, 'ig-id');
          sd.igVersion = this.getExtensionStringValue(item, 'ig-version');
          sd.isCurrent = false;

          if (this.getExtensionBoolValue(item, 'ig-current')) {
            sd.isCurrent = true;
          } else {
            sd.canonical += `|${sd.igVersion}`;
          }

          this.supportedProfiles.set(sd.canonical, sd);
        });
        this.updateProfileFilter();
      }
      if (parameter.name == 'llmProvider' && parameter.extension) {
        this.showAIAnalyzeButton = true;
      }
    });
    od.parameter
      .filter((f) => f.use == 'in' && f.name != 'resource' && f.name != 'profile' && f.name != 'ig' && f.name != 'llmProvider')
      .forEach((parameter: fhir.r4.OperationDefinitionParameter) => {
        this.validatorSettings.set(parameter.name, new ValidationParameterDefinition(parameter));
      });
  }

  /**
   * Analyzes the current URL to detect if there is a validation request in the search parameters ('resource',
   * 'profile', others).
   * It runs before the component has fully loaded, so we can't compare the profile with the list of supported profiles.
   * @private
   */
  private async analyzeUrlForValidation(): Promise<void> {
    if (!window.location.hash) {
      return;
    }
    const searchParams = new URLSearchParams(window.location.hash.substring(1));
    if (searchParams.has('resource')) {
      let hasSetProfile = false;
      if (searchParams.has('profile')) {
        this.selectedProfile = <string>searchParams.get('profile');
        hasSetProfile = true;
      }

      const resource = Base64.decode(searchParams.get('resource'));
      let contentType = 'application/fhir+json';
      if (resource.startsWith('<')) {
        contentType = 'application/fhir+xml';
      }

      let filename: string;
      if (searchParams.has('filename')) {
        filename = searchParams.get('filename');
      } else {
        filename = `provided.${contentType.split('+')[1]}`;
      }

      for (const [key, value] of searchParams) {
        if (key === 'resource' || key === 'profile' || key === 'filename') {
          continue;
        }
        if (this.validatorSettings.has(key)) {
          this.validatorSettings.get(key).formControl.setValue(value);
        }
      }

      this.validateResource(filename, resource, contentType, !hasSetProfile && !this.profileLocked);
      this.toastr.info('Validation', 'The validation of your resource has started', {
        closeButton: true,
        timeOut: 3000,
      });
    }
  }
}

/**
 * Struct of an uploaded file.
 */
class UploadedFile {
  constructor(
    public filename: string,
    public contentType: string,
    public content: string,
    public resourceType: string
  ) {
  }
}

/**
 * Enum of the different tabs in the code editor.
 */
export enum CodeEditorContent {
  RESOURCE_CONTENT,
  OPERATION_OUTCOME,
}
