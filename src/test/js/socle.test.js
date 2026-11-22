'use strict';
// Tests du socle : node --test src/test/js/  (aucune dependance)
const test = require('node:test');
const assert = require('node:assert/strict');
const socle = require('../../main/resources/static/js/socle.js');

const BASE = 'https://site.example/page/accueil';
const ORIGINE = 'https://site.example';

function faussesBalises(metas) {
  return {
    querySelector(sel) {
      const m = /^meta\[name="([^"]+)"\]$/.exec(sel);
      if (!m || !(m[1] in metas)) return null;
      const contenu = metas[m[1]];
      return { getAttribute: (n) => (n === 'content' ? contenu : null) };
    },
  };
}

function fauxDocument() {
  return {
    createElement(tag) {
      const attrs = {};
      return {
        tagName: tag,
        className: '',
        textContent: '',
        children: [],
        setAttribute(n, v) { attrs[n] = v; },
        getAttribute(n) { return attrs[n]; },
        appendChild(c) { this.children.push(c); return c; },
      };
    },
  };
}

test('memeSite : meme site', () => {
  assert.equal(socle.memeSite('/x', BASE, ORIGINE), true);
  assert.equal(socle.memeSite('x/y?z=1', BASE, ORIGINE), true);
  assert.equal(socle.memeSite('https://site.example/x', BASE, ORIGINE), true);
});

test('memeSite : autres sites et pieges', () => {
  for (const c of [
    'https://autre.example/x',
    '//autre.example/x',
    'javascript:alert(1)',
    'https://site.example.evil.com/',
    'https://site.example@evil.com/',
    'http://site.example/x',
  ]) {
    assert.equal(socle.memeSite(c, BASE, ORIGINE), false, c);
  }
});

test('memeSite : entree illisible ou vide sans exception', () => {
  assert.equal(socle.memeSite('', 'pas une base', ORIGINE), false);
  assert.equal(socle.memeSite('http://', 'pas une base', ORIGINE), false);
  assert.equal(socle.memeSite(undefined, undefined, undefined), false);
  assert.equal(socle.memeSite(null, null, null), false);
  assert.equal(socle.memeSite({}, BASE, ORIGINE), false);
});

test('doitAjouterJeton : verbes modifiants sur le meme site', () => {
  for (const v of ['post', 'POST', 'put', 'patch', 'delete', 'Delete']) {
    assert.equal(socle.doitAjouterJeton(v, '/x', BASE, ORIGINE), true, v);
  }
});

test('doitAjouterJeton : verbes surs', () => {
  for (const v of ['get', 'GET', 'head', 'Head', 'options', 'trace', 'TRACE']) {
    assert.equal(socle.doitAjouterJeton(v, '/x', BASE, ORIGINE), false, v);
  }
});

test('doitAjouterJeton : autre site', () => {
  assert.equal(socle.doitAjouterJeton('post', 'https://autre.example/x', BASE, ORIGINE), false);
  assert.equal(socle.doitAjouterJeton('post', '//autre.example/x', BASE, ORIGINE), false);
});

test('lireJeton : deux balises presentes', () => {
  const d = faussesBalises({ _csrf_header: 'X-CSRF-TOKEN', _csrf: 'abc' });
  assert.deepEqual(socle.lireJeton(d), { entete: 'X-CSRF-TOKEN', valeur: 'abc' });
});

test('lireJeton : balise manquante ou vide', () => {
  assert.equal(socle.lireJeton(faussesBalises({ _csrf: 'abc' })), null);
  assert.equal(socle.lireJeton(faussesBalises({ _csrf_header: 'X' })), null);
  assert.equal(socle.lireJeton(faussesBalises({ _csrf_header: '', _csrf: 'abc' })), null);
  assert.equal(socle.lireJeton(faussesBalises({ _csrf_header: 'X', _csrf: '' })), null);
  assert.equal(socle.lireJeton(faussesBalises({})), null);
});

test('creerMessage : le texte reste du texte', () => {
  const piege = '<img src=x onerror=alert(1)>';
  const el = socle.creerMessage(fauxDocument(), piege);
  const textes = [];
  (function parcourir(n) {
    if (typeof n.textContent === 'string' && n.textContent) textes.push(n.textContent);
    n.children.forEach(parcourir);
  })(el);
  assert.deepEqual(textes, [piege]);
  assert.equal(el.innerHTML, undefined);
  (function aucunImg(n) {
    assert.notEqual(n.tagName, 'img');
    n.children.forEach(aucunImg);
  })(el);
  assert.match(el.className, /toast/);
  assert.equal(el.getAttribute('role'), 'alert');
});
