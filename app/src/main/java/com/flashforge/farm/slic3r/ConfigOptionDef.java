package com.flashforge.farm.slic3r;

import android.text.TextUtils;

import androidx.annotation.Keep;

@Keep
public class ConfigOptionDef {
    public String key;

    // What type? bool, int, string etc.
    public ConfigOptionType type = ConfigOptionType.NONE;

    // Usually empty.
    // Special values - "i_enum_open", "f_enum_open" to provide combo box for int or float selection,
    // "select_open" - to open a selection dialog (currently only a serial port selection).
    public GUIType guiType;

    // Label of the GUI input field.
    // In case the GUI input fields are grouped in some views, the label defines a short label of a grouped value,
    // while full_label contains a label of a stand-alone field.
    // The full label is shown, when adding an override parameter for an object or a modified object.
    public String label;
    public String fullLabel;

    // With which printer technology is this configuration valid?
    public PrinterTechnology printerTechnology = PrinterTechnology.UNKNOWN;

    // Category of a configuration field, from the GUI perspective.
    // One of: "Layers and Perimeters", "Infill", "Support material", "Speed", "Extruders", "Advanced", "Extrusion Width"
    public String category;

    // A tooltip text shown in the GUI.
    public String tooltip;

    // Text right from the input field, usually a unit of measurement.
    public String sidetext;

    // True for multiline strings.
    public boolean multiline;

    // For text input: If true, the GUI text box spans the complete page width.
    public boolean fullWidth;

    // Not editable. Currently only used for the display of the number of threads.
    public boolean readonly = false;

    // Height of a multiline GUI text box.
    public int height = -1;

    // Optional width of an input field.
    public int width = -1;

    // <min, max> limit of a numeric input.
    // If not set, the <min, max> is set to <INT_MIN, INT_MAX>
    // By setting min=0, only nonnegative input is allowed.
    public float min = Float.MIN_VALUE;
    public float max = Float.MAX_VALUE;

    public ConfigOptionMode mode = ConfigOptionMode.SIMPLE;

    public String defaultValue;

    public String[] enumLabels;
    public String[] enumValues;

    public String getLabel() {
        return TextUtils.isEmpty(label) ? fullLabel : label;
    }

    public String getFullLabel() {
        return TextUtils.isEmpty(fullLabel) ? label : fullLabel;
    }

    ConfigOptionDef() {}

    public enum ConfigOptionType {
        NONE,
        // single float
        FLOAT,
        // vector of floats
        FLOATS(true),
        // single int
        INT,
        // vector of ints
        INTS(true),
        // single string
        STRING,
        // vector of strings
        STRINGS(true),
        // percent value. Currently only used for infill.
        PERCENT,
        // percents value. Currently used for retract before wipe only.
        PERCENTS(true),
        // a fraction or an absolute value
        FLOAT_OR_PERCENT,
        // vector of the above
        FLOATS_OR_PERCENTS(true),
        // single 2d point (Point2f). Currently not used.
        POINT,
        // vector of 2d points (Point2f). Currently used for the definition of the print bed and for the extruder offsets.
        POINTS(true),
        POINT3,
        // single boolean value
        BOOL,
        // vector of boolean values
        BOOLS(true),
        // a generic enum
        ENUM,
        // vector of enum values
        ENUMS;

        public final boolean list;

        ConfigOptionType() {
            this(false);
        }

        ConfigOptionType(boolean list) {
            this.list = list;
        }
    }

    public enum GUIType {
        UNDEFINED,
        // Open enums, integer value could be one of the enumerated values or something else.
        I_ENUM_OPEN,
        // Open enums, float value could be one of the enumerated values or something else.
        F_ENUM_OPEN,
        // Open enums, string value could be one of the enumerated values or something else.
        SELECT_OPEN,
        // Color picker, string value.
        COLOR,
        // Currently unused.
        SLIDER,
        // Static text
        LEGEND,
        // Vector value, but edited as a single string.
        ONE_STRING,
        // Close parameter, string value could be one of the list values.
        SELECT_CLOSE,
        // Password, string vaule is hidden by asterisk.
        PASSWORD
    }

    public enum PrinterTechnology {
        // Fused Filament Fabrication
        FFF,
        // Stereolitography
        SLA,
        // Unknown, useful for command line processing
        UNKNOWN,
        // Any technology, useful for parameters compatible with both ptFFF and ptSLA
        ANY
    }

    public enum ConfigOptionMode {
        SIMPLE,
        ADVANCED,
        EXPERT,
        UNDEFINED
    }
}
