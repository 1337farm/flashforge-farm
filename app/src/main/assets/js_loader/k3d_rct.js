localStorage.setItem('bedX', $['bed_x']);
localStorage.setItem('bedY', $['bed_y']);
localStorage.setItem('zOffset', $['z_offset']);

localStorage.setItem('hotendTemperature', $['temperature']);
localStorage.setItem('bedTemperature', $['bed_temperature']);
localStorage.setItem('flow', $['extrusion_multiplier'] * 100);
localStorage.setItem('cooling', $['min_fan_speed']);
localStorage.setItem('lineWidth', $['nozzle_diameter[0]']);
localStorage.setItem('firstLayerLineWidth', Math.round($['nozzle_diameter[0]'] * 1.5 * 10) / 10);
localStorage.setItem('layerHeight', Math.round($['nozzle_diameter[0]'] * 0.5 * 10) / 10);
localStorage.setItem('printSpeed', $['perimeter_speed']);
localStorage.setItem('firstLayerPrintSpeed', $['first_layer_speed']);
localStorage.setItem('travelSpeed', $['travel_speed']);

localStorage.setItem('firmwareMarlin', $['gcode_flavor'] == 'marlin2' || $['gcode_flavor'] == 'marlin');
localStorage.setItem('firmwareKlipper', $['gcode_flavor'] == 'klipper');
localStorage.setItem('firmwareRRF', $['gcode_flavor'] == 'reprap' || $['gcode_flavor'] == 'reprapFirmware');
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

saveTextAsFile = function(filename, text) {
    FarmApp.beginDownload(filename);
    FarmApp.writeData(btoa(unescape(encodeURIComponent(text))));
    FarmApp.finishDownload();
}