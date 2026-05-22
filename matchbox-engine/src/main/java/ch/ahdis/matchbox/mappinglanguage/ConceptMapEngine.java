package ch.ahdis.matchbox.mappinglanguage;

/*
  Copyright (c) 2011+, HL7, Inc.
  All rights reserved.
  
  Redistribution and use in source and binary forms, with or without modification, 
  are permitted provided that the following conditions are met:
    
   * Redistributions of source code must retain the above copyright notice, this 
     list of conditions and the following disclaimer.
   * Redistributions in binary form must reproduce the above copyright notice, 
     this list of conditions and the following disclaimer in the documentation 
     and/or other materials provided with the distribution.
   * Neither the name of HL7 nor the names of its contributors may be used to 
     endorse or promote products derived from this software without specific 
     prior written permission.
  
  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND 
  ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED 
  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. 
  IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, 
  INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT 
  NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR 
  PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, 
  WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) 
  ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE 
  POSSIBILITY OF SUCH DAMAGE.
  
 */



import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.r5.context.IWorkerContext;
import org.hl7.fhir.r5.model.CodeType;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.ConceptMap;
import org.hl7.fhir.r5.model.ConceptMap.ConceptMapGroupComponent;
import org.hl7.fhir.r5.model.ConceptMap.SourceElementComponent;
import org.hl7.fhir.r5.model.ConceptMap.TargetElementComponent;
import org.hl7.fhir.r5.model.Enumerations.ConceptMapRelationship;
import org.hl7.fhir.r5.model.Parameters;
import org.hl7.fhir.r5.model.UriType;
import org.hl7.fhir.utilities.CanonicalPair;

import ch.ahdis.matchbox.engine.MatchboxEngine;

public class ConceptMapEngine {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ConceptMapEngine.class);

  private IWorkerContext context;
  private MatchboxEngine.TranslateMode translateMode;

  public ConceptMapEngine(IWorkerContext context) {
    this.context = context;
    this.translateMode = MatchboxEngine.TranslateMode.FALLBACK;
  }

  public ConceptMapEngine(IWorkerContext context, MatchboxEngine.TranslateMode translateMode) {
    this.context = context;
    this.translateMode = translateMode;
  }

  public Coding translate(Coding source, String url) throws FHIRException {
    ConceptMap cm = context.fetchResource(ConceptMap.class, url);
    if (cm == null)
      throw new FHIRException("Unable to find ConceptMap '"+url+"'");
    if (translateMode == MatchboxEngine.TranslateMode.SERVER)
      return translateViaTxServer(source, cm);
    Coding result;
    if (source.hasSystem())
      result = translateBySystem(cm, source.getSystem(), source.getCode());
    else
      result = translateByJustCode(cm, source.getCode());
    if (result == null && translateMode == MatchboxEngine.TranslateMode.FALLBACK)
      result = translateViaTxServer(source, cm);
    return result;
  }

  private Coding translateViaTxServer(Coding source, ConceptMap cm) {
    try {
      // Use reflection to access getTxClientManager() to avoid class-identity issues
      // between the matchbox-bundled BaseWorkerContext and the external HL7 jar's version.
      Method getTxClientManager = context.getClass().getMethod("getTxClientManager");
      Object tcm = getTxClientManager.invoke(context);
      if (tcm == null) return null;

      Method hasClient = tcm.getClass().getMethod("hasClient");
      if (!(Boolean) hasClient.invoke(tcm)) {
        log.warn("No terminology client configured, cannot fall back to tx server for translate");
        return null;
      }

      Method getMasterClient = tcm.getClass().getMethod("getMasterClient");
      Object client = getMasterClient.invoke(tcm);
      if (client == null) return null;

      String system = source.hasSystem() ? source.getSystem() : null;
      String targetsystem = null;
      if (!cm.getGroup().isEmpty()) {
        ConceptMapGroupComponent group = cm.getGroup().get(0);
        if (system == null && group.hasSource()) system = group.getSource();
        if (group.hasTarget()) targetsystem = group.getTarget();
      }

      Parameters params = new Parameters();
      if (system != null) params.addParameter("system", new UriType(system));
      params.addParameter("code", new CodeType(source.getCode()));
      if (targetsystem != null) params.addParameter("targetsystem", new UriType(targetsystem));

      Method translate = client.getClass().getMethod("translate", Parameters.class);
      Parameters response = (Parameters) translate.invoke(client, params);
      boolean matched = response.getParameterBool("result");
      if (!matched) return null;

      for (Parameters.ParametersParameterComponent p : response.getParameter()) {
        if ("match".equals(p.getName())) {
          for (Parameters.ParametersParameterComponent part : p.getPart()) {
            if ("concept".equals(part.getName()) && part.getValue() instanceof Coding c) {
              return c;
            }
          }
        }
      }
    } catch (Exception e) {
      log.warn("Terminology server translate failed for ConceptMap {} code {}: {}: {}", cm.getUrl(), source.getCode(), e.getClass().getName(), e.getMessage());
    }
    return null;
  }

  private Coding translateByJustCode(ConceptMap cm, String code) throws FHIRException {
    SourceElementComponent ct = null;
    ConceptMapGroupComponent cg = null;
    for (ConceptMapGroupComponent g : cm.getGroup()) {
      for (SourceElementComponent e : g.getElement()) {
        if (code.equals(e.getCode())) {
          if (e != null)
            throw new FHIRException("Unable to process translate "+code+" because multiple candidate matches were found in concept map "+cm.getUrl());
          ct = e;
          cg = g;
        }
      }
    }
    if (ct == null)
      return null;
    TargetElementComponent tt = null;
    for (TargetElementComponent t : ct.getTarget()) {
      if (!t.hasDependsOn() && !t.hasProduct() && isOkRelationship(t.getRelationship())) {
        if (tt != null)
          throw new FHIRException("Unable to process translate "+code+" because multiple targets were found in concept map "+cm.getUrl());
        tt = t;       
      }
    }
    if (tt == null)
      return null;
    CanonicalPair cp = new CanonicalPair(cg.getTarget());
    return new Coding().setSystem(cp.getUrl()).setVersion(cp.getVersion()).setCode(tt.getCode()).setDisplay(tt.getDisplay());      
  }

  private boolean isOkRelationship(ConceptMapRelationship relationship) {
    return relationship != null && relationship != ConceptMapRelationship.NOTRELATEDTO;
  }

  private Coding translateBySystem(ConceptMap cm, String system, String code) {
    throw new Error("Not done yet");
  }

}