# OpenMRS REST Representation Analyzer — Project Context

## Project Goals & Motivation

- **Purpose:** Automatically generate accurate, up-to-date OpenAPI 3.0 specifications for OpenMRS REST APIs at build time, directly from Java code.
- **Motivation:**
  - Enable frontend and integration developers to discover and use OpenMRS REST APIs without reading backend code.
  - Support client code generation, API documentation, and validation.
  - Make API docs version-aware and module-scoped.
  - Replace manual, error-prone OpenAPI documentation with automated, type-safe generation.
  - Support module-specific OpenAPI generation for any OpenMRS module with REST resources.

## Current Implementation Status

### ✅ **Completed Features:**
- **Maven Plugin Structure:** `RepresentationAnalyzerMojo` orchestrates build-time analysis with configurable parameters
- **Schema Introspection Service:** `SchemaIntrospectionServiceImpl` provides accurate property type discovery using reflection
- **Swagger-Core Integration:** Uses `io.swagger.core.v3:swagger-models` for type-safe OpenAPI 3.0 model building
- **Contextual REST Discovery:** Automatically discovers domain types actually exposed via REST (no hardcoded lists)
- **Representation-Based Generation:** Supports `default`, `full`, `ref`, and `custom` representations with distinct schemas and discriminators
- **Property Type Discovery:** Leverages reflection to discover fields, getters, and `@PropertyGetter`/`@PropertySetter` annotations
- **Comprehensive Error Responses:** Includes 200, 400, 401, 404 response codes for all endpoints
- **Collection Type Support:** Handles `List<T>`, `Set<T>`, and `Collection<T>` with proper array schemas
- **Robust Error Handling:** Graceful failure handling with comprehensive logging and validation
- **Production-Ready Output:** Clean, professional OpenAPI 3.0 specification suitable for client generation
- **Module Filtering:** Configurable filtering to analyze only resources from specific target modules
- **Custom Representation Support:** Documents GraphQL-like custom representations with all available properties
- **Type Hierarchy Handling:** Proper handling of `DelegatingResourceHandler` inheritance and `Resource` interface casting

### 🔄 **Current Implementation:**
- **Test File:** `OpenmrsOpenapiSpecGeneratorTest.java` - Complete implementation using Swagger-Core + SchemaIntrospectionService + Contextual Discovery + Module Filtering
- **Dependencies:** 
  - `io.swagger.core.v3:swagger-models` (test scope)
  - `io.swagger.core.v3:swagger-core` (test scope)
  - `org.slf4j:slf4j-api` and `org.slf4j:slf4j-simple`
  - `org.springframework:spring-core` (for reflection utilities)
- **Architecture:** Build-time plugin that forks JVM process to run analysis with configurable module targeting
- **Output:** `target/openapi-spec-output.json` - Complete OpenAPI 3.0 specification

## How the Plugin Works (High-Level)

- **Maven Plugin:** This project is a Maven plugin (`openmrs-rest-analyzer`) that can be included in any OpenMRS module's `pom.xml`.
- **Build-Time Analysis:** When the plugin runs (e.g., during `mvn install`), it:
  1. Forks a JVM with the module's classpath (including the OpenMRS version specified by the module).
  2. Loads all REST resource handlers present in the module.
  3. **Module Filtering:** Filters resources based on configuration (package-based, annotation-based, or hybrid).
  4. **Contextual Discovery:** Builds a set of domain types actually exposed via REST (no hardcoded lists).
  5. Uses `SchemaIntrospectionService` to discover all available properties and their Java types.
  6. Analyzes each resource's supported representations (`default`, `full`, `ref`, `custom`).
  7. Generates type-safe OpenAPI schemas using Swagger-Core models with proper `$ref` relationships.
  8. Outputs a standards-compliant OpenAPI 3.0 spec (JSON by default) to `target/openapi-spec-output.json`.

## Key Technical Components

### **1. Module Filtering System**
```java
// Configuration parameters in Mojo
@Parameter(property = "targetModule", defaultValue = "webservices.rest")
private String targetModule;
@Parameter(property = "basePackage", defaultValue = "org.openmrs.module.webservices.rest")
private String basePackage;
@Parameter(property = "analyzeAllModules", defaultValue = "false")
private boolean analyzeAllModules;
@Parameter(property = "moduleFilter", defaultValue = "package")
private String moduleFilter; // "package", "annotation", or "hybrid"

// Filtering logic in test
private boolean isResourceFromTargetModule(DelegatingResourceHandler<?> handler) {
    if (includeAllResources) {
        return true;
    }
    boolean isFromTargetModule = false;
    switch (moduleFilter.toLowerCase()) {
        case "package":
            isFromTargetModule = isFromTargetPackage(handler);
            break;
        case "annotation":
            isFromTargetModule = isFromTargetAnnotation(handler);
            break;
        case "hybrid":
            isFromTargetModule = isFromTargetPackage(handler) || isFromTargetAnnotation(handler);
            break;
        default:
            isFromTargetModule = isFromTargetPackage(handler);
    }
    return isFromTargetModule;
}
```

**Benefits:**
- **Module-specific analysis:** Generate OpenAPI specs for specific modules (e.g., bedmanagement, webservices.rest)
- **Flexible filtering:** Package-based, annotation-based, or hybrid approaches
- **Configurable:** Users can specify target module and filtering strategy
- **No cross-module contamination:** Ensures clean, focused API documentation

### **2. Contextual REST Discovery**
```java
// Build domain type set from actual REST resource handlers
private void buildRestDomainTypeSet(RestService restService) {
    Collection<DelegatingResourceHandler<?>> handlers = restService.getResourceHandlers();
    for (DelegatingResourceHandler<?> handler : handlers) {
        if (handler instanceof org.openmrs.module.webservices.rest.web.resource.api.Resource) {
            Class<?> delegateType = schemaIntrospectionService.getDelegateType(handler);
            if (delegateType != null) {
                restDomainTypes.add(delegateType.getSimpleName());
            }
        }
    }
}

// Use discovered types for $ref generation
private boolean isOpenMRSDomainType(String javaType) {
    return restDomainTypes.contains(javaType);
}
```

**Benefits:**
- **No hardcoded domain type lists** - automatically adapts to any OpenMRS module
- **100% accuracy** - only includes types actually exposed via REST
- **Module-agnostic** - works with core, modules, and custom resources
- **Future-proof** - automatically picks up new domain types
- **Type hierarchy safe** - handles `DelegatingResourceHandler` inheritance properly

### **3. SchemaIntrospectionService**
```java
// Core interface for property discovery
public interface SchemaIntrospectionService {
    Class<?> getDelegateType(Resource resource);
    Map<String, String> discoverAvailableProperties(Class<?> delegateType);
    Map<String, String> discoverResourceProperties(Resource resource);
}
```

**Features:**
- Discovers delegate types from `DelegatingResourceHandler<T>`
- Uses reflection to find public fields and JavaBean getters
- Handles `@PropertyGetter` and `@PropertySetter` annotations
- Traverses inheritance hierarchy for complete property discovery
- Returns property names mapped to Java type names
- **Robust and dependable** - handles complex OpenMRS patterns

### **4. Custom Representation Support**
```java
// Generate custom representation schema with all available properties
private Schema<?> generateCustomRepresentationSchema(DelegatingResourceHandler<?> handler, 
                                                    Map<String, String> allProperties, 
                                                    Components components) {
    ObjectSchema customSchema = new ObjectSchema();
    Map<String, Schema> properties = new HashMap<>();
    
    // Include all available properties for custom representation
    for (Map.Entry<String, String> entry : allProperties.entrySet()) {
        String propertyName = entry.getKey();
        String javaType = entry.getValue();
        Schema<?> propertySchema = mapToSwaggerSchema(javaType);
        properties.put(propertyName, propertySchema);
    }
    
    customSchema.setProperties(properties);
    return customSchema;
}
```

**Benefits:**
- **GraphQL-like flexibility:** Documents all available properties for custom representations
- **Inheritance support:** Includes properties from superclasses (e.g., PatientResource can access PersonResource properties)
- **Complete documentation:** Users know exactly what properties are available for custom requests
- **Future-proof:** Automatically adapts to new properties added to resources

### **5. Swagger-Core Integration**
```java
// Type-safe OpenAPI model building
OpenAPI openAPI = new OpenAPI();
Components components = new Components();
Paths paths = new Paths();

// Create schemas using proper methods (not deprecated addProperties)
ObjectSchema schema = new ObjectSchema();
Map<String, Schema> properties = new HashMap<>();
properties.put("uuid", new StringSchema());
properties.put("age", new IntegerSchema());
schema.setProperties(properties);
```

**Benefits:**
- Type-safe OpenAPI model construction
- No deprecated method usage
- Proper generic type handling
- Jackson serialization for final output

### **6. Representation-Based Schema Generation**
```java
// Generate distinct schemas for each representation with discriminators
for (String repName : Arrays.asList("default", "full", "ref", "custom")) {
    Representation representation = getRepresentationByName(repName);
    Schema<?> schema = generateRepresentationSchema(handler, representation, allProperties, components);
    String schemaName = capitalize(resourceType) + capitalize(repName); // e.g., "PatientDefault"
    components.addSchemas(schemaName, schema);
}

// Add discriminator for polymorphic responses
ObjectSchema responseSchema = new ObjectSchema();
responseSchema.setOneOf(oneOfSchemas);
Discriminator discriminator = new Discriminator();
discriminator.setPropertyName("v");
discriminator.setMapping(mapping);
responseSchema.setDiscriminator(discriminator);
```

### **7. Comprehensive Error Handling & Validation**
```java
// Robust error handling with specific exception types
private boolean processResourceHandler(DelegatingResourceHandler<?> handler, Components components, Paths paths) {
    try {
        // Process handler logic
        return true; // Success
    } catch (IllegalArgumentException | SecurityException | IllegalStateException | StringIndexOutOfBoundsException e) {
        log.warn("Error processing resource handler {}: {}", handler.getClass().getSimpleName(), e.getMessage());
        return false; // Failure
    }
}

// Validation of generated OpenAPI structure
private void validateOpenApiStructure(OpenAPI openAPI) {
    assertNotNull(openAPI, "OpenAPI object should not be null");
    assertNotNull(openAPI.getInfo(), "OpenAPI info should not be null");
    // Additional validation...
}
```

## Ideal Workflow: Step-by-Step

### **Step 1: User Configuration**
```xml
<!-- In bedmanagement module's pom.xml -->
<plugin>
    <groupId>org.openmrs.plugin</groupId>
    <artifactId>openmrs-rest-analyzer</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <configuration>
        <targetModule>bedmanagement</targetModule>
        <moduleFilter>annotation</moduleFilter>
    </configuration>
</plugin>
```

### **Step 2: Plugin Execution**
```bash
mvn openmrs-rest-analyzer:analyze-representations
```

### **Step 3: Resource Discovery & Filtering**
- Plugin discovers all REST resource handlers
- Filters based on `@Resource` annotation name containing target module
- Only processes bedmanagement resources: `BedResource`, `BedTypeResource`, `BedAssignmentResource`

### **Step 4: Representation Analysis**
- For each filtered resource, analyzes `default`, `full`, `ref`, and `custom` representations
- Uses `SchemaIntrospectionService` to discover all available properties
- Generates distinct schemas for each representation

### **Step 5: OpenAPI Generation**
- Creates type-safe OpenAPI 3.0 specification using Swagger-Core models
- Includes proper `$ref` relationships for domain types
- Adds discriminators for polymorphic responses
- Generates comprehensive error responses

### **Step 6: Output**
- Produces `bedmanagement-openapi.json` with module-specific API documentation
- Ready for client generation, API documentation, and validation

## How to Use in a Consumer Module

1. **Set the OpenMRS version in your module's POM:**
   ```xml
   <properties>
     <openmrs.version>2.5.0</openmrs.version>
   </properties>
   ```

2. **Ensure all OpenMRS dependencies use this property:**
   ```xml
   <dependencyManagement>
     <dependencies>
       <dependency>
         <groupId>org.openmrs.api</groupId>
         <artifactId>openmrs-api</artifactId>
         <version>${openmrs.version}</version>
         <type>pom</type>
         <scope>import</scope>
       </dependency>
     </dependencies>
   </dependencyManagement>
   ```

3. **Add the plugin to your build:**
   ```xml
   <plugin>
     <groupId>org.openmrs.plugin</groupId>
     <artifactId>openmrs-rest-analyzer</artifactId>
     <version>1.0.0</version>
     <executions>
       <execution>
         <goals>
           <goal>analyze-representations</goal>
         </goals>
       </execution>
     </executions>
     <configuration>
       <openmrsVersion>${openmrs.version}</openmrsVersion>
       <targetModule>your-module-name</targetModule>
       <moduleFilter>annotation</moduleFilter>
       <outputFile>target/your-module-openapi.json</outputFile>
     </configuration>
   </plugin>
   ```

4. **Build your module:**
   ```sh
   mvn clean install
   ```

5. **Find the output:**
   - The OpenAPI spec will be in `target/your-module-openapi.json`.
   - The `info.version` field will match your OpenMRS version.

## OpenAPI Spec Generation Process

### **Current Implementation (Swagger-Core + Contextual Discovery + Module Filtering):**
- **Module Filtering:** Configurable filtering to analyze only target module resources
- **Contextual REST Discovery:** Builds domain type set from actual resource handlers
- **Swagger-Core Models:** Build type-safe OpenAPI 3.0 models using `io.swagger.core.v3:swagger-models`
- **Schema Introspection:** Use `SchemaIntrospectionService` for accurate property type discovery
- **Custom Representations:** Support for GraphQL-like custom representation documentation
- **Jackson Serialization:** Use Jackson for final JSON output (recommended by Swagger-Core)
- **Comprehensive Validation:** Ensures generated spec is complete and valid
- **Pros:** Type-safe, spec-compliant, maintainable, follows best practices, no hardcoded types, module-specific
- **Cons:** Requires additional dependency, slightly more complex setup

### **Legacy Approach (Manual Jackson):**
- **Manual JSON Building:** Uses Jackson's `ObjectNode`/`ArrayNode` for manual JSON construction
- **Hardcoded Domain Types:** Required manual maintenance of domain type lists
- **Pros:** Flexible, no extra dependencies, easy for custom output
- **Cons:** No type safety, more manual work, harder to maintain, brittle

## Key Technical Decisions

### **1. Module Filtering over Universal Analysis**
- **Decision:** Implement configurable module filtering to generate module-specific OpenAPI specs
- **Reason:** Enables focused, clean API documentation for specific modules
- **Benefits:** No cross-module contamination, easier maintenance, better user experience

### **2. Contextual REST Discovery over Hardcoded Lists**
- **Decision:** Use `RestService.getResourceHandlers()` to discover domain types dynamically
- **Reason:** Eliminates hardcoded lists, works with any module, future-proof
- **Benefits:** 100% accuracy, module-agnostic, automatically adapts to changes

### **3. Library Choice: Swagger-Core over SmallRye**
- **Decision:** Chose `io.swagger.core.v3:swagger-models` over `smallrye-open-api-core`
- **Reason:** SmallRye not available as standalone library in Maven Central
- **Benefits:** Official OpenAPI 3.0 models, well-maintained, comprehensive

### **4. Property Discovery: SchemaIntrospectionService**
- **Decision:** Created custom reflection-based property discovery service
- **Reason:** Need to discover properties from OpenMRS domain objects and REST handlers
- **Benefits:** Handles inheritance, annotations, and complex OpenMRS patterns

### **5. Serialization: Jackson over Swagger-Core Utils**
- **Decision:** Use Jackson directly instead of deprecated `io.swagger.v3.core.util.Json`
- **Reason:** Swagger-Core team recommends Jackson for serialization
- **Benefits:** Better performance, more control, future-proof

### **6. Method Usage: setProperties() over addProperties()**
- **Decision:** Use `setProperties(Map<String, Schema>)` instead of deprecated `addProperties()`
- **Reason:** Better performance, type safety, follows current best practices
- **Benefits:** Bulk property setting, proper generic handling

### **7. Error Handling: Specific Exception Types**
- **Decision:** Use multi-catch with specific exception types instead of broad `Exception`
- **Reason:** Better error handling, clearer stack traces, follows user preferences
- **Benefits:** More precise error handling, better debugging

### **8. Custom Representation Documentation**
- **Decision:** Include custom representation support with all available properties
- **Reason:** Provides complete documentation for GraphQL-like custom representations
- **Benefits:** Users know exactly what properties are available, supports inheritance

## Best Practices & Extensibility

- **Zero-config by default:** Works out-of-the-box for most modules
- **Module-specific analysis:** Configurable filtering for focused API documentation
- **Contextual discovery:** No hardcoded types, automatically adapts to any module
- **Type-safe generation:** Uses Swagger-Core models for compile-time safety
- **Accurate property discovery:** Leverages reflection for complete type information
- **Representation-aware:** Generates distinct schemas for different representations
- **Custom representation support:** Documents all available properties for flexible queries
- **Production-ready:** Comprehensive error handling and validation
- **CI/CD friendly:** Works in headless, automated environments
- **Extensible:** Can be enhanced for custom representations, error responses, YAML output

## Key Configuration Options

- `openmrsVersion`: The OpenMRS version to target (default: `2.4.x`)
- `targetModule`: The target module to analyze (default: `webservices.rest`)
- `basePackage`: Base package for package-based filtering (default: `org.openmrs.module.webservices.rest`)
- `analyzeAllModules`: Whether to analyze all modules (default: `false`)
- `moduleFilter`: Filtering strategy - "package", "annotation", or "hybrid" (default: `package`)
- `outputFile`: Where to write the OpenAPI spec (default: `target/openapi-spec-output.json`)
- `outputDirectory`: Output directory (default: `${project.build.directory}`)
- (Future) `recursionDepth`, `includeResources`, `excludeResources`, etc.

## Current File Structure

```
openmrs-rest-representation-analyzer/
├── src/
│   ├── main/java/org/openmrs/plugin/rest/analyzer/
│   │   └── RepresentationAnalyzerMojo.java          # Main Maven plugin with module filtering
│   └── test/java/org/openmrs/plugin/rest/analyzer/
│       ├── test/
│       │   └── OpenmrsOpenapiSpecGeneratorTest.java    # Current implementation (contextual discovery + module filtering)
│       └── introspection/
│           ├── SchemaIntrospectionService.java         # Property discovery interface
│           └── SchemaIntrospectionServiceImpl.java     # Reflection-based implementation
├── pom.xml                                           # Dependencies and build config
├── README.md                                         # User documentation
└── context.md                                        # This file
```

## Pros & Cons

| Pros                                              | Cons / Caveats                                  |
|---------------------------------------------------|-------------------------------------------------|
| Module-specific analysis (no cross-module contamination) | Requires additional dependency (swagger-models) |
| Contextual REST discovery (no hardcoded types)    | More complex setup than manual JSON approach    |
| Type-safe OpenAPI generation with Swagger-Core    | Must keep dependencies and version property in sync |
| Accurate property discovery via reflection        | Advanced OpenAPI features require more work     |
| Custom representation support                      | Named/custom representations not yet auto-detected |
| Representation-aware schema generation            | Performance overhead for large codebases        |
| Comprehensive error handling and validation       | Requires OpenMRS REST module dependencies       |
| Production-ready output                           | Limited to GET operations currently             |
| CI/CD friendly                                    | Module filtering requires proper configuration  |
| Flexible filtering strategies                     | Annotation-based filtering may not work for all modules |

## Important Notes for New Contributors

- **Always keep `<openmrs.version>` and your dependencies in sync**
- **The plugin analyzes the code on your classpath:** If you want docs for a different OpenMRS version, change your dependencies and property
- **Use the current implementation:** `OpenmrsOpenapiSpecGeneratorTest.java` is the most up-to-date approach
- **Module filtering is key:** It provides focused, clean API documentation for specific modules
- **Contextual discovery is essential:** It provides accurate domain type detection without hardcoded lists
- **SchemaIntrospectionService is robust:** It provides accurate property type discovery that replaces manual type detection
- **Avoid deprecated methods:** Use `setProperties()` instead of `addProperties()`, Jackson instead of Swagger-Core utils
- **Test domain type discovery:** Add assertions to verify that expected domain types are discovered
- **Custom representations are supported:** The plugin documents all available properties for flexible queries
- **The plugin is under active development:** Contributions and suggestions are welcome!

## Migration Path

### **From Legacy to Current:**
1. **Replace hardcoded domain type lists** with contextual REST discovery
2. **Replace manual Jackson JSON building** with Swagger-Core models
3. **Use SchemaIntrospectionService** instead of manual type detection
4. **Add module filtering** for focused, module-specific analysis
5. **Update method calls** to use non-deprecated approaches
6. **Add comprehensive error handling and validation**
7. **Leverage representation-based generation** for accurate API documentation
8. **Include custom representation support** for complete property documentation

### **Future Enhancements:**
- Support for custom/named representations
- HTTP method discovery (POST, PUT, DELETE)
- Recursive schema generation for complex nested types
- Performance optimizations for large codebases
- YAML output format
- Custom response schemas
- Module-specific configuration options
- Advanced filtering strategies
- Support for OpenMRS 3.x REST module

## Testing Strategy

### **Module Filtering Testing**
```java
@Test
public void testModuleFiltering() {
    // Test package-based filtering
    assertTrue(isFromTargetPackage(bedResource), "BedResource should be from target package");
    assertFalse(isFromTargetPackage(patientResource), "PatientResource should not be from target package");
    
    // Test annotation-based filtering
    assertTrue(isFromTargetAnnotation(bedResource), "BedResource should have target annotation");
    assertFalse(isFromTargetAnnotation(patientResource), "PatientResource should not have target annotation");
}
```

### **Domain Type Discovery Testing**
```java
@Test
public void testRestDomainTypesDiscovery() {
    List<String> expectedTypes = Arrays.asList("Patient", "Person", "Encounter");
    for (String type : expectedTypes) {
        assertTrue(restDomainTypes.contains(type), "Domain type should be discovered: " + type);
    }
}
```

### **Custom Representation Testing**
```java
@Test
public void testCustomRepresentationSchema() {
    Schema<?> customSchema = generateCustomRepresentationSchema(handler, allProperties, components);
    assertNotNull(customSchema, "Custom schema should be generated");
    assertTrue(customSchema.getProperties().size() > 0, "Custom schema should have properties");
}
```

### **OpenAPI Output Validation**
- Verify that `target/openapi-spec-output.json` is generated
- Check that schemas include expected domain types
- Confirm paths match your REST endpoints
- Validate against OpenAPI 3.0 specification
- Verify module filtering produces focused output

### **Error Handling Testing**
- Test with malformed resources
- Verify graceful failure handling
- Check comprehensive logging output
- Validate error recovery
- Test type hierarchy edge cases

---

This file should be read by any new contributor or thread to get up to speed on the project's goals, architecture, usage, and best practices. The project has evolved significantly to support module-specific OpenAPI generation with robust filtering, custom representations, and comprehensive error handling. 