// Помощник движка SLAM внутри страницы Microsoft Forms.
//
// Kotlin (SlamEngine) вызывает его через WebView.evaluateJavascript:
// window.__slam.<функция>(...) и получает результат как JSON. Сам ход
// заполнения, ожидания и проверки — в Kotlin; здесь только работа с DOM.
//
// Селекторы и ловушки формы перенесены из SLAM-backend/engine/slam_filler.py,
// где они проверены на живой форме. Не «упрощать» без проверки на форме.
(function () {
  if (window.__slam) return;

  var S = {
    QITEM: '[data-automation-id="questionItem"]',
    TITLE: '[data-automation-id="questionTitle"]',
    TEXT: '[data-automation-id="textInput"]',
    CHOICE: '[data-automation-id="choiceItem"]',
    NEXT: '[data-automation-id="nextButton"]',
    SUBMIT: '[data-automation-id="submitButton"]',
    SECTION: '[data-automation-id="sectionTitle"]',
    REQUIRED: '[data-automation-id="requiredStar"]'
  };

  function all(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }
  function norm(s) { return String(s || '').split(/\s+/).filter(Boolean).join(' ').toLowerCase(); }
  // Варианты трёхъязычные: «Да/ Иә/Yes». Сравниваем по частям, а не
  // подстрокой — иначе «да» найдётся внутри «нет данных».
  function segments(t) { return String(t || '').split(/[\/|]/).map(norm).filter(Boolean); }
  function text(el) { return el ? String(el.innerText || el.textContent || '') : ''; }

  function qitems() { return all(S.QITEM); }
  function titleOf(q) {
    var t = q && q.querySelector(S.TITLE);
    return t ? text(t).trim().replace(/\n/g, ' ') : '';
  }
  // Вопрос по маркеру заголовка; если маркер не задан — по номеру среди всех вопросов.
  function question(spec) {
    if (spec && spec.marker) {
      var m = norm(spec.marker), qs = qitems();
      for (var i = 0; i < qs.length; i++) if (norm(titleOf(qs[i])).indexOf(m) >= 0) return qs[i];
      return null;
    }
    if (spec && typeof spec.index === 'number') return qitems()[spec.index] || null;
    return null;
  }
  function section() {
    var el = document.querySelector(S.SECTION);
    return el ? text(el).trim() : '';
  }
  // Отпечаток страницы: раздел + заголовки вопросов. Меняется при переходе.
  function sig() {
    var qs = all(S.TITLE);
    return section() + '\u0001' + qs.length + '\u0001' +
      qs.map(function (q) { return text(q).trim().slice(0, 60); }).join('\u0002');
  }
  function startButton() {
    var names = ['начать', 'start now', 'начать сейчас', 'бастау'], found = null;
    all('button,[role=button]').forEach(function (b) {
      var n = norm(b.getAttribute('aria-label') || text(b));
      for (var i = 0; i < names.length; i++) if (n === names[i] || n.indexOf(names[i] + ' ') === 0) found = b;
    });
    return found;   // последняя подходящая, как btn.last в серверном движке
  }

  // Состояние варианта: true/false, null — форма его не сообщает.
  function isChecked(el) {
    if (!el) return null;
    var known = false, els = [el, el.querySelector('[role=radio],[role=checkbox]')];
    for (var i = 0; i < els.length; i++) {
      if (!els[i]) continue;
      var v = els[i].getAttribute('aria-checked');
      if (v !== null) { known = true; if (v === 'true') return true; }
    }
    var inp = el.matches && el.matches('input') ? el : el.querySelector('input[type=radio],input[type=checkbox]');
    if (inp) return !!inp.checked;
    return known ? false : null;
  }
  function clickChoice(c) {
    var r = c.querySelector('input[type=radio],input[type=checkbox],[role=radio],[role=checkbox]') || c;
    r.click();
  }

  // Поле ввода вопроса; если по заголовку не нашлось — pos-е поле по порядку
  // (pos < 0 — без запасного пути).
  function textField(marker, pos) {
    var q = marker ? question({ marker: marker }) : null;
    var el = q ? q.querySelector(S.TEXT) : null;
    if (el) return { el: el, byTitle: true, title: titleOf(q) };
    if (pos >= 0) {
      var f = all(S.TEXT)[pos];
      if (f) return { el: f, byTitle: false, title: '' };
    }
    return null;
  }
  // Внутреннее значение React-поля — то, что форма реально отправит.
  function reactValue(el) {
    var keys = Object.keys(el);
    for (var i = 0; i < keys.length; i++) {
      if (keys[i].indexOf('__reactProps$') === 0) {
        var p = el[keys[i]];
        return p && typeof p.value === 'string' ? p.value : null;
      }
    }
    return null;
  }
  // Ввод как у пользователя: штатный сеттер value (его отслеживает React) + события.
  function setText(el, value) {
    el.focus();
    var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, value);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    el.blur();
  }

  function findChoice(q, wanted) {
    var want = norm(wanted), cs = all(S.CHOICE, q), i;
    for (i = 0; i < cs.length; i++) if (segments(text(cs[i])).indexOf(want) >= 0) return cs[i];
    for (i = 0; i < cs.length; i++) if (norm(text(cs[i])).indexOf(want) >= 0) return cs[i];
    return null;
  }

  // ВАЖНО: «Другое» в MS Forms — НЕ choiceItem, а отдельный radio в одном
  // <label> со свободным полем (на живой форме: choiceItem'ов 29, radio 30).
  // «Последний вариант списка» — это реальная компания Eurohydroservice.
  function otherRadio(q) {
    var r = null, i;
    try { r = q.querySelector('label:has(' + S.TEXT + ') [role=radio]'); } catch (e) { r = null; }
    if (!r) {
      var labels = all('label', q);
      for (i = 0; i < labels.length && !r; i++) if (labels[i].querySelector(S.TEXT)) r = labels[i].querySelector('[role=radio]');
    }
    if (!r) {
      var rs = all('[role=radio]', q);
      for (i = 0; i < rs.length; i++) {
        var lab = norm(rs[i].getAttribute('aria-label'));
        if (/other|друг|басқа/.test(lab)) { r = rs[i]; break; }
      }
    }
    return r;
  }

  window.__slam = {
    state: function () {
      return {
        sig: sig(), section: section(),
        texts: all(S.TEXT).length,
        next: !!document.querySelector(S.NEXT),
        submit: !!document.querySelector(S.SUBMIT),
        start: !!startButton(),
        ready: document.readyState, url: location.href
      };
    },
    sig: sig,
    section: section,
    // Для отчёта об ошибке: что сейчас на экране формы.
    snapshot: function () {
      return {
        url: location.href, ready: document.readyState, section: section(),
        texts: all(S.TEXT).length, start: !!startButton(),
        body: text(document.body).split(/\s+/).filter(Boolean).join(' ').slice(0, 300)
      };
    },
    clickStart: function () { var b = startButton(); if (b) b.click(); return !!b; },
    hasQuestion: function (marker) { return !!question({ marker: marker }); },

    fillText: function (marker, pos, value) {
      var f = textField(marker, pos);
      if (!f) return { ok: false, error: 'поле не найдено' };
      setText(f.el, value);
      return { ok: true, byTitle: f.byTitle, title: f.title };
    },
    readText: function (marker, pos) {
      var f = textField(marker, pos);
      if (!f) return { ok: false, error: 'поле не найдено' };
      return { ok: true, actual: f.el.value, react: reactValue(f.el) };
    },

    choiceQuestions: function () {
      var out = [];
      qitems().forEach(function (q, i) { if (q.querySelector(S.CHOICE)) out.push({ index: i, title: titleOf(q) }); });
      return out;
    },
    // Нажать вариант. Возвращает его полный текст (или found:false).
    pick: function (spec, wanted) {
      var q = question(spec);
      if (!q) return { found: false, noQuestion: true };
      var c = findChoice(q, wanted);
      if (!c) return { found: false, title: titleOf(q) };
      clickChoice(c);
      return { found: true, text: text(c).split(/\s+/).filter(Boolean).join(' '), title: titleOf(q) };
    },
    isChecked: function (spec, wanted) {
      var q = question(spec), c = q && findChoice(q, wanted);
      return c ? isChecked(c) : false;
    },

    companyInfo: function (marker) {
      var q = question({ marker: marker });
      if (!q) return { found: false };
      return { found: true, title: titleOf(q), other: !!otherRadio(q), input: !!q.querySelector(S.TEXT) };
    },
    clickOther: function (marker) {
      var q = question({ marker: marker }), r = q && otherRadio(q);
      if (r) r.click();
      return !!r;
    },
    otherChecked: function (marker) {
      var q = question({ marker: marker }), r = q && otherRadio(q);
      return r ? isChecked(r) : false;
    },

    clickNext: function () { var b = document.querySelector(S.NEXT); if (b) b.click(); return !!b; },
    clickSubmit: function () { var b = document.querySelector(S.SUBMIT); if (b) b.click(); return !!b; },
    alerts: function () {
      var out = [];
      all('[role=alert]').forEach(function (a) {
        var t = text(a).split(/\s+/).filter(Boolean).join(' ');
        if (t && out.indexOf(t) < 0) out.push(t);
      });
      return out;
    },
    confirmed: function (markers) {
      var body = norm(text(document.body));
      for (var i = 0; i < markers.length; i++) if (body.indexOf(markers[i]) >= 0) return true;
      return false;
    },
    // Обязательные текстовые поля текущей страницы, оставшиеся пустыми.
    requiredEmpty: function () {
      var out = [];
      qitems().forEach(function (q) {
        var ti = q.querySelector(S.TEXT);
        if (!ti || !q.querySelector(S.REQUIRED)) return;
        if (!String(ti.value || '').trim()) out.push(titleOf(q).slice(0, 60));
      });
      return out;
    }
  };
})();
