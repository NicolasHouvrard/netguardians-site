// Derive le nom de l'element personnalise de la version declaree dans le manifeste du plugin :
// `clients-app-v<version encodee>` (meme regle que PluginLoaderService.nomElement du noyau). Encodage injectif :
// '.' -> '-', '-' -> '_d', tout autre caractere hors [a-z0-9] -> '_u<hex>_' ; ex. 1.1.10 -> v1-1-10, 1.11.0 -> v1-11-0.
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ici = dirname(fileURLToPath(import.meta.url));
const manifeste = readFileSync(join(ici, '..', '..', 'src', 'main', 'resources', 'plugin.properties'), 'utf8');
const lire = cle => manifeste.split(/\r?\n/).map(l => l.match(new RegExp(`^${cle}=(.*)$`))).find(Boolean)?.[1].trim();
const nom = lire('nom');
const version = lire('version');
if (!nom || !version) throw new Error('plugin.properties : nom ou version introuvable');
const suffixe = Array.from(version.toLowerCase())
  .map(c => (/[a-z0-9]/.test(c) ? c : c === '.' ? '-' : c === '-' ? '_d' : `_u${c.codePointAt(0).toString(16)}_`))
  .join('');
writeFileSync(
  join(ici, '..', 'src', 'app', 'element-name.generated.ts'),
  `// Genere par scripts/version.mjs depuis plugin.properties : ne pas editer, ne pas commiter.\nexport const NOM_ELEMENT = '${nom}-app-v${suffixe}';\n`,
);
