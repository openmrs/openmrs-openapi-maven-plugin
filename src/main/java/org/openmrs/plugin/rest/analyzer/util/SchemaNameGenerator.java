package org.openmrs.plugin.rest.analyzer.util;

import org.apache.commons.lang3.StringUtils;
import java.util.Locale;

/**
 * Centralized utility for generating consistent schema names in OpenAPI specifications.
 * This class ensures that schema definition names and $ref reference names always match,
 * preventing the broken reference issues that occur when different parts of the code
 * use different naming strategies.
 */
public final class SchemaNameGenerator {
    
    private static final Locale LOCALE = Locale.ROOT;
    
    /**
     * Transforms a resource or delegate type name to kebab-case base name.
     * This preserves the current behavior used in processResourceHandler.
     * 
     * Examples:
     * - "QueueRoomResource" -> "Queue-room"
     * - "QueueResource" -> "Queue" 
     * - "PatientResource" -> "Patient"
     * - "QueueRoom" -> "Queue-room" (for delegate types)
     * 
     * @param typeName the class simple name (e.g., "QueueRoomResource" or "QueueRoom")
     * @return kebab-case base name with first letter capitalized
     */
    public static String toKebabCase(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return "Unknown";
        }
        
        // Remove "Resource" suffix if present (for resource classes)
        String base = typeName.endsWith("Resource") 
            ? typeName.substring(0, typeName.length() - "Resource".length())
            : typeName;
        
        // Apply kebab-case transformation (matches current processResourceHandler logic)
        // This regex inserts a hyphen before each uppercase letter
        String kebab = base.replaceAll("([A-Z])", "-$1").toLowerCase(LOCALE);
        
        // Remove leading hyphen if present
        if (kebab.startsWith("-")) {
            kebab = kebab.substring(1);
        }
        
        // Capitalize first letter to match existing "Queue-room" style
        return StringUtils.capitalize(kebab);
    }
    
    /**
     * Generates the final schema name by combining the kebab-case base name 
     * with the representation name.
     * 
     * Examples:
     * - ("QueueRoomResource", "default") -> "Queue-roomDefault"
     * - ("QueueResource", "full") -> "QueueFull"
     * - ("PatientResource", "ref") -> "PatientRef"
     * 
     * @param typeName the class simple name (resource or delegate type)
     * @param representation the representation name ("default", "full", "ref", etc.)
     * @return the final schema name for use in components/schemas
     */
    public static String schemaName(String typeName, String representation) {
        if (representation == null || representation.isEmpty()) {
            representation = "default";
        }
        
        String kebabBase = toKebabCase(typeName);
        String capitalizedRep = StringUtils.capitalize(representation.toLowerCase(LOCALE));
        
        return kebabBase + capitalizedRep;
    }
    
    /**
     * Validates if a schema name follows the expected pattern.
     * This can be used for testing and validation purposes.
     * 
     * @param schemaName the schema name to validate
     * @return true if the name follows the expected pattern
     */
    public static boolean isValidSchemaName(String schemaName) {
        if (schemaName == null || schemaName.isEmpty()) {
            return false;
        }
        
        // Expected pattern: starts with capital letter, may contain hyphens, 
        // ends with capitalized representation (Default, Full, Ref, etc.)
        return schemaName.matches("^[A-Z][a-z-]*[A-Z][a-z]+$") || 
               schemaName.matches("^[A-Z][a-z]+$"); // Single word resources like "Queue"
    }
    
    /**
     * Extracts the representation part from a schema name.
     * Used for debugging and validation.
     * 
     * @param schemaName the full schema name (e.g., "Queue-roomDefault")
     * @return the representation part (e.g., "Default") or null if not found
     */
    public static String extractRepresentation(String schemaName) {
        if (schemaName == null || schemaName.isEmpty()) {
            return null;
        }
        
        // Look for common representation suffixes
        String[] representations = {"Default", "Full", "Ref", "Custom"};
        for (String rep : representations) {
            if (schemaName.endsWith(rep)) {
                return rep;
            }
        }
        
        return null;
    }
    
    /**
     * Extracts the base name (without representation) from a schema name.
     * Used for debugging and validation.
     * 
     * @param schemaName the full schema name (e.g., "Queue-roomDefault")
     * @return the base name part (e.g., "Queue-room") or the original name if no representation found
     */
    public static String extractBaseName(String schemaName) {
        String representation = extractRepresentation(schemaName);
        if (representation != null) {
            return schemaName.substring(0, schemaName.length() - representation.length());
        }
        return schemaName;
    }
}
