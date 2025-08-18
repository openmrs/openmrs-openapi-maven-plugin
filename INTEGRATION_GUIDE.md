# OpenMRS OpenAPI Generator Plugin - Integration Guide

## Overview
This plugin generates OpenAPI 3.0 specifications for any OpenMRS module's REST resources. The plugin uses JUnit 5 Jupiter framework with Spring web context to introspect and analyze REST resources.

## Prerequisites

### 1. OpenMRS Platform Version
- **Minimum Required**: OpenMRS Platform 2.4.0+
- **Recommended**: OpenMRS Platform 2.4.6-SNAPSHOT or later
- **Reason**: JUnit 5 Jupiter support requires OpenMRS 2.4+

### 2. Required Dependencies in Target Module's POM

Add these dependencies to your module's `omod/pom.xml` file:

```xml
<dependencies>
    <!-- OpenMRS Web Test Support (required for Jupiter web context) -->
    <dependency>
        <groupId>org.openmrs.web</groupId>
        <artifactId>openmrs-web</artifactId>
        <version>${openmrsPlatformVersion}</version>
        <classifier>tests</classifier>
        <scope>test</scope>
    </dependency>

    <!-- OpenMRS API Test Support (required for domain model access) -->
    <dependency>
        <groupId>org.openmrs.api</groupId>
        <artifactId>openmrs-api</artifactId>
        <version>${openmrsPlatformVersion}</version>
        <classifier>tests</classifier>
        <scope>test</scope>
    </dependency>

    <!-- OpenMRS Test Framework (JUnit 5 Jupiter support) -->
    <dependency>
        <groupId>org.openmrs.test</groupId>
        <artifactId>openmrs-test</artifactId>
        <version>${openmrsPlatformVersion}</version>
        <type>pom</type>
        <scope>test</scope>
    </dependency>

    <!-- Servlet API (required for web context initialization) -->
    <dependency>
        <groupId>javax.servlet</groupId>
        <artifactId>javax.servlet-api</artifactId>
        <version>4.0.1</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### 3. Plugin Configuration

Add this to your module's `omod/pom.xml` plugins section:

```xml
<plugin>
    <groupId>org.openmrs.plugin</groupId>
    <artifactId>openmrs-rest-analyzer</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <executions>
        <execution>
            <id>generate-openapi-spec</id>
            <phase>process-classes</phase>
            <goals>
                <goal>analyze-representations</goal>
            </goals>
            <configuration>
                <!-- Optional: Specify packages to scan -->
                <scanPackages>
                    <scanPackage>org.openmrs.module.yourmodule.web.resources</scanPackage>
                </scanPackages>
            </configuration>
        </execution>
    </executions>
</plugin>
```

## Usage

### Generate OpenAPI Specification
```bash
mvn clean process-classes
```

### Output Location
The generated OpenAPI specification will be created at:
```
target/openapi/[module-name]-openapi-spec.json
```

## Troubleshooting

### Common Issues

1. **ClassNotFoundException: BaseModuleWebContextSensitiveTest**
   - **Solution**: Ensure OpenMRS Platform version is 2.4.0 or later
   - **Add**: openmrs-test dependency with type=pom

2. **NoClassDefFoundError: javax.servlet.SessionCookieConfig**
   - **Solution**: Add javax.servlet-api dependency with scope=test

3. **Spring context initialization fails**
   - **Solution**: Ensure openmrs-web tests dependency is present
   - **Verify**: All test dependencies have scope=test

### Version Compatibility Matrix

| OpenMRS Platform | Plugin Version | Status |
|------------------|----------------|--------|
| 2.3.x and below | Not Supported  | ❌     |
| 2.4.0 - 2.4.5    | 1.0.0-SNAPSHOT | ✅     |
| 2.4.6-SNAPSHOT+  | 1.0.0-SNAPSHOT | ✅ Recommended |

## Examples

### Queue Module Integration
See `openmrs-module-queue` for a complete example of successful integration.

### webservices.rest Module Integration  
See `openmrs-module-webservices.rest` omod-2.4 and omod-2.5 for reference implementations.

---

## Support
For issues or questions, please check the troubleshooting section above or examine the working examples in the supported modules.
