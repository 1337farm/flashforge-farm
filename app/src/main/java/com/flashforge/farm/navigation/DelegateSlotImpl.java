package com.flashforge.farm.navigation;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import com.flashforge.farm.R;
import com.flashforge.farm.fragment.BedFragment;
import com.flashforge.farm.fragment.FilamentConfigFragment;
import com.flashforge.farm.fragment.PrintConfigFragment;
import com.flashforge.farm.fragment.PrinterConfigFragment;
import com.flashforge.farm.fragment.SettingsFragment;

public abstract class DelegateSlotImpl extends NavigationDelegate {
    public int getSlotCount() {
        return 6;
    }

    @DrawableRes
    public int getSlotIcon(int slot) {
        switch (slot) {
            default:
            case 0:
                return R.drawable.view_in_ar_24;
            case 1:
                return R.drawable.wrench_outline_28;
            case 2:
                return R.drawable.slot_filament_28;
            case 3:
                return R.drawable.printer_outline_28;
            case 4:
                return R.drawable.view_in_ar_24; // Use an existing icon for now, could be R.drawable.printer_outline_28
            case 5:
                return R.drawable.settings_outline_28;
        }
    }

    public boolean needDisplaySlotGear(int slot) {
        return slot != 0 && slot != 4 && slot != 5;
    }

    @StringRes
    public int getSlotTitle(int slot) {
        switch (slot) {
            default:
            case 0:
                return R.string.SlotBed;
            case 1:
                return R.string.SlotPrintConfig;
            case 2:
                return R.string.SlotFilamentConfig;
            case 3:
                return R.string.SlotPrinterConfig;
            case 4:
                return R.string.SlotFleet;
            case 5:
                return R.string.SlotAppSettings;
        }
    }

    @StringRes
    public int getSlotTooltip(int slot) {
        switch (slot) {
            default:
                return getSlotTitle(slot);
            case 1:
                return R.string.SlotPrintConfigTooltip;
            case 2:
                return R.string.SlotFilamentConfigTooltip;
            case 3:
                return R.string.SlotPrinterConfigTooltip;
            case 4:
                return R.string.SlotFleetTooltip;
            case 5:
                return R.string.SlotAppSettingsTooltip;
        }
    }

    @Override
    public Fragment newFragment(int slot) {
        switch (slot) {
            default:
            case 0:
                return new BedFragment();
            case 1:
                return new PrintConfigFragment();
            case 2:
                return new FilamentConfigFragment();
            case 3:
                return new PrinterConfigFragment();
            case 4:
                return new com.flashforge.farm.fragment.FleetFragment();
            case 5:
                return new SettingsFragment();
        }
    }
}
