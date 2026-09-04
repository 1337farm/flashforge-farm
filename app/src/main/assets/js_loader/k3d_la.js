localStorage.setItem('k3d_la_bedX', $['bed_x']);
localStorage.setItem('k3d_la_bedY', $['bed_y']);
localStorage.setItem('k3d_la_zOffset', $['z_offset']);

localStorage.setItem('k3d_la_hotendTemperature', $['temperature']);
localStorage.setItem('k3d_la_bedTemperature', $['bed_temperature']);
localStorage.setItem('k3d_la_flow', $['extrusion_multiplier'] * 100);
localStorage.setItem('k3d_la_cooling', $['min_fan_speed']);
localStorage.setItem('k3d_la_lineWidth', $['nozzle_diameter[0]']);
localStorage.setItem('k3d_la_firstLayerLineWidth', Math.round($['nozzle_diameter[0]'] * 1.5 * 10) / 10);
localStorage.setItem('k3d_la_layerHeight', Math.round($['nozzle_diameter[0]'] * 0.5 * 10) / 10);
localStorage.setItem('k3d_la_firstLayerSpeed', $['first_layer_speed']);
localStorage.setItem('k3d_la_travelSpeed', $['travel_speed']);
localStorage.setItem('k3d_la_numPerimeters', $['perimeters']);

localStorage.setItem('k3d_la_firmwareMarlin', $['gcode_flavor'] == 'marlin2' || $['gcode_flavor'] == 'marlin');
localStorage.setItem('k3d_la_firmwareKlipper', $['gcode_flavor'] == 'klipper');
localStorage.setItem('k3d_la_firmwareRRF', $['gcode_flavor'] == 'reprap' || $['gcode_flavor'] == 'reprapFirmware');
loadForm();

var style = document.createElement('style');
style.textContent = `
    [data-md-color-scheme=slate][data-md-color-primary=teal] {
        --md-typeset-a-color: $['color_accent'];
    }
    [data-md-color-primary=teal] {
        --md-primary-fg-color: $['color_accent'];
    }
    [data-md-color-scheme=slate] {
        --md-default-bg-color: $['window_background_dark'];
    }
    [data-md-color-scheme=default] {
       --md-default-bg-color: $['window_background_light'];
    }
    .caliButtonTable td {
        padding-left: 0;
        padding-right: 0;
    }
    .caliButton {
        padding-left: 16px;
        padding-right: 16px;
    }
`;
document.head.appendChild(style);

var dark = $['is_dark_theme'];
document.getElementsByClassName('md-option')[dark ? 1 : 0].click();

beginSaveFile = function(filename) {
    FarmApp.beginDownload(filename);
}

writeToFile = function(data) {
    FarmApp.writeData(btoa(unescape(encodeURIComponent(data))));
}

finishFile = function() {
    FarmApp.finishDownload();
}