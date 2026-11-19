// Derive le nom de l'element personnalise de la version declaree dans le manifeste du plugin :
// `clients-app-v<version sans points>` (meme regle que PluginLoaderService.nomElement du noyau).
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ici = dirname(fileURLToPath(import.meta.url));
const manifeste = readFileSync(join(ici, '..', '..', 'src', 'main', 'resources', 'plugin.properties'), 'utf8');
const lire = cle => manifeste.split(/\r?\n/).map(l => l.match(new RegExp(`^${cle}=(.*)$`))).find(Boolean)?.[1].trim();
const nom = lire('nom');
const version = lire('version');
if (!nom || !version) throw new Error('plugin.properties : nom ou version introuvable');
const suffixe = version.toLowerCase().replace(/\./g, '').replace(/[^a-z0-9-]/g, '-');
writeFileSync(
  join(ici, '..', 'src', 'app', 'element-name.generated.ts'),
  `// Genere par scripts/version.mjs depuis plugin.properties : ne pas editer, ne pas commiter.\nexport const NOM_ELEMENT = '${nom}-app-v${suffixe}';\n`,
);
