# Embed matchbox-engine in a Java application

In this tutorial, you will create a small Java application that validates a FHIR resource with
[matchbox-engine](../explanation/architecture.md), the library at the core of Matchbox, without running a server.

You need:

- Java 17 or newer (JDK);
- [Maven](https://maven.apache.org/);
- an internet connection the first time (Maven downloads the dependencies).

## Step 1: create the project

Create a directory `matchbox-demo` with the following `pom.xml`. Use the
[latest version](https://central.sonatype.com/artifact/health.matchbox/matchbox-engine) of matchbox-engine:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>example</groupId>
  <artifactId>matchbox-demo</artifactId>
  <version>1.0</version>

  <properties>
    <maven.compiler.release>17</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <dependencies>
    <dependency>
      <groupId>health.matchbox</groupId>
      <artifactId>matchbox-engine</artifactId>
      <version>5.0.0</version>
    </dependency>
  </dependencies>
</project>
```

## Step 2: write the application

Create the file `src/main/java/example/Demo.java`:

```java
package example;

import ch.ahdis.matchbox.engine.MatchboxEngine;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.model.utilities.formats.FhirFormat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class Demo {
  public static void main(String[] args) throws Exception {
    // 1. Create an engine for FHIR R4
    MatchboxEngine engine = new MatchboxEngine.MatchboxEngineBuilder().getEngineR4();

    // 2. The resource to validate: Patient.name must be a list, not an object
    String patient = """
        {"resourceType": "Patient", "name": {"family": "Test"}}
        """;

    // 3. Validate it against the base Patient profile
    OperationOutcome outcome = engine.validate(
        new ByteArrayInputStream(patient.getBytes(StandardCharsets.UTF_8)),
        FhirFormat.JSON,
        "http://hl7.org/fhir/StructureDefinition/Patient");

    // 4. Print the issues
    for (var issue : outcome.getIssue()) {
      System.out.println(issue.getSeverity() + ": " + issue.getDiagnostics());
    }
  }
}
```

## Step 3: run it

```bash
mvn compile exec:java -Dexec.mainClass=example.Demo
```

Creating the engine takes a few seconds: it loads FHIR R4 and the terminology and extension packages that are bundled
with matchbox-engine. Then the application prints the issues, among them:

```
ERROR: The property name must be a JSON Array, not an Object (at Patient)
```

## Step 4: fix the resource

Change the resource to `{"resourceType": "Patient", "name": [{"family": "Test"}]}` and run the application again: the
error is gone.

## What you have learned

- `MatchboxEngineBuilder` creates an engine for a FHIR version; the engine works offline with the bundled packages.
- `MatchboxEngine.validate()` returns an `OperationOutcome`, like the `$validate` operation of the server.

## Next steps

- [Use matchbox-engine](../how-to/use-matchbox-engine.md) to load implementation guides, transform with maps and
  convert CDA documents.
- The [Javadoc](https://ahdis.github.io/matchbox/apidocs/) describes the whole API.
