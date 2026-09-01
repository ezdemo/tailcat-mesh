(function ($) {
    'use strict';

    function closeSidebar() {
        $('#sidebar').removeClass('is-open');
        $('#sidebar-overlay').removeClass('is-open');
    }

    $('[data-sidebar-open]').on('click', function () {
        $('#sidebar').addClass('is-open');
        $('#sidebar-overlay').addClass('is-open');
    });
    $('[data-sidebar-close], #sidebar-overlay').on('click', closeSidebar);
    $('.nav-item').on('click', closeSidebar);

    $('[data-confirm]').on('submit', function (event) {
        var message = $(this).data('confirm');
        if (message && !window.confirm(message)) {
            event.preventDefault();
        }
    });

    $('.copy-button').on('click', function () {
        var button = $(this);
        var target = button.data('copy-target');
        var value = target ? $(target).text().trim() : button.data('copy');
        if (!value || !navigator.clipboard) {
            window.alert('当前浏览器不支持自动复制，请手动选择文本。');
            return;
        }
        navigator.clipboard.writeText(value).then(function () {
            var original = button.text();
            button.text('已复制');
            window.setTimeout(function () { button.text(original); }, 1800);
        }).catch(function () {
            window.alert('无法复制，请手动选择文本。');
        });
    });

    $('#device-search').on('input', function () {
        var query = $(this).val().toString().trim().toLowerCase();
        $('[data-device-row]').each(function () {
            var row = $(this);
            row.toggle(!query || (row.data('device-search') || '').toString().toLowerCase().indexOf(query) >= 0);
        });
    });

    $('.flash').each(function () {
        var flash = $(this);
        window.setTimeout(function () { flash.fadeOut(300); }, 4800);
    });

    var refresh = parseInt($('body').data('auto-refresh'), 10);
    if (refresh > 0) {
        window.setTimeout(function () { window.location.reload(); }, refresh);
    }

    $('form[data-disable-on-submit]').on('submit', function () {
        $(this).find('button[type="submit"]').prop('disabled', true);
    });
}(jQuery));
