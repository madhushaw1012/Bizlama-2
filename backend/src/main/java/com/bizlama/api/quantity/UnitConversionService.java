package com.bizlama.api.quantity;

import com.bizlama.api.domain.Ingredient;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Authoritative deterministic conversion for operational quantities.
 *
 * <p>Mass is persisted as grams, volume as millilitres, and discrete goods as
 * each. The service deliberately has no mass/volume conversion: that would
 * require an ingredient-specific density and must never be guessed.</p>
 */
@Service
public class UnitConversionService {

    private static final MathContext CALCULATION_CONTEXT = MathContext.DECIMAL128;

    private static final Map<String, UnitDefinition> UNITS = unitRegistry();

    public CanonicalQuantity canonicalize(BigDecimal quantity, String sourceUnit) {
        requireQuantity(quantity);
        UnitDefinition definition = definition(sourceUnit);
        return new CanonicalQuantity(
                quantity.multiply(definition.toCanonical(), CALCULATION_CONTEXT)
                        .stripTrailingZeros(),
                canonicalUnit(definition.dimension()),
                definition.dimension()
        );
    }

    public CanonicalQuantity convert(
            BigDecimal quantity,
            String sourceUnit,
            String targetUnit
    ) {
        requireQuantity(quantity);
        UnitDefinition source = definition(sourceUnit);
        UnitDefinition target = definition(targetUnit);

        if (source.dimension() != target.dimension()) {
            throw new IncompatibleUnitException(
                    "Cannot convert " + sourceUnit + " to " + targetUnit
                            + ": the units have different dimensions."
            );
        }

        BigDecimal canonical = quantity.multiply(
                source.toCanonical(),
                CALCULATION_CONTEXT
        );

        return new CanonicalQuantity(
                canonical.divide(target.toCanonical(), CALCULATION_CONTEXT)
                        .stripTrailingZeros(),
                target.symbol(),
                target.dimension()
        );
    }

    public CanonicalQuantity toIngredientBase(
            Ingredient ingredient,
            BigDecimal quantity,
            String sourceUnit
    ) {
        if (ingredient == null) {
            throw new IllegalArgumentException("Ingredient is required.");
        }

        UnitDefinition source = definition(sourceUnit);
        UnitDefinition target = definition(ingredient.baseUnit());

        if (source.dimension() != target.dimension()) {
            throw new IncompatibleUnitException(
                    "Unit " + sourceUnit + " is not compatible with "
                            + ingredient.name() + " (expected "
                            + canonicalUnit(target.dimension()) + ")."
            );
        }

        CanonicalQuantity canonical = canonicalize(quantity, sourceUnit);
        return new CanonicalQuantity(
                canonical.quantity(),
                canonicalUnit(target.dimension()),
                target.dimension()
        );
    }

    public boolean compatible(String firstUnit, String secondUnit) {
        return definition(firstUnit).dimension() == definition(secondUnit).dimension();
    }

    public String canonicalUnit(String unit) {
        return canonicalUnit(definition(unit).dimension());
    }

    private static String canonicalUnit(UnitDimension dimension) {
        return switch (dimension) {
            case MASS -> "g";
            case VOLUME -> "ml";
            case COUNT -> "each";
        };
    }

    private static void requireQuantity(BigDecimal quantity) {
        if (quantity == null) {
            throw new IllegalArgumentException("Quantity is required.");
        }
    }

    private static UnitDefinition definition(String rawUnit) {
        if (rawUnit == null || rawUnit.isBlank()) {
            throw new IncompatibleUnitException("Unit is required.");
        }

        String key = rawUnit.trim()
                .toLowerCase(Locale.ROOT)
                .replace(".", "");
        UnitDefinition result = UNITS.get(key);

        if (result == null) {
            throw new IncompatibleUnitException(
                    "Unsupported unit '" + rawUnit
                            + "'. Review the quantity instead of guessing a conversion."
            );
        }

        return result;
    }

    private static Map<String, UnitDefinition> unitRegistry() {
        Map<String, UnitDefinition> result = new LinkedHashMap<>();
        add(result, "mg", UnitDimension.MASS, "0.001", "milligram", "milligrams");
        add(result, "g", UnitDimension.MASS, "1", "gram", "grams", "gm", "gms");
        add(result, "kg", UnitDimension.MASS, "1000", "kilogram", "kilograms", "kgs");
        add(result, "ml", UnitDimension.VOLUME, "1", "millilitre", "millilitres",
                "milliliter", "milliliters");
        add(result, "l", UnitDimension.VOLUME, "1000", "litre", "litres", "liter",
                "liters", "ltr", "ltrs");
        add(result, "each", UnitDimension.COUNT, "1", "piece", "pieces", "pc", "pcs",
                "count", "counts", "unit", "units", "no", "nos");
        return Map.copyOf(result);
    }

    private static void add(
            Map<String, UnitDefinition> registry,
            String symbol,
            UnitDimension dimension,
            String toCanonical,
            String... aliases
    ) {
        UnitDefinition definition = new UnitDefinition(
                symbol,
                dimension,
                new BigDecimal(toCanonical)
        );
        registry.put(symbol, definition);
        for (String alias : aliases) {
            registry.put(alias, definition);
        }
    }

    private record UnitDefinition(
            String symbol,
            UnitDimension dimension,
            BigDecimal toCanonical
    ) {
    }
}
