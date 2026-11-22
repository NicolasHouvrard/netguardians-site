# Polices hébergées

Polices servies par le site lui-même (aucun CDN, aucun appel externe). Fichiers woff2 « latin », poids 400 et 700,
téléchargés une fois depuis les paquets Fontsource (https://fontsource.org) via jsDelivr.

| Police | Version | Source | Licence |
|---|---|---|---|
| Inter | @fontsource/inter 5.0.8 | https://cdn.jsdelivr.net/npm/@fontsource/inter@5.0.8/files/ | SIL OFL 1.1 (`OFL-inter.txt`) |
| Roboto | @fontsource/roboto 5.0.8 | https://cdn.jsdelivr.net/npm/@fontsource/roboto@5.0.8/files/ | Apache 2.0 (`LICENSE-Apache-2.0-roboto.txt`) |
| Source Sans 3 | @fontsource/source-sans-3 5.0.12 | https://cdn.jsdelivr.net/npm/@fontsource/source-sans-3@5.0.12/files/ | SIL OFL 1.1 (`OFL-source-sans-3.txt`) |

## Sommes SHA-256

```
0364d368abf457d4e70dbc7a7a360f3486eaea2837b194915b23d4398bee91ac  inter-latin-400-normal.woff2
ced2d8e02e2fbf08d2edec9b5f13648ed8348588a05f7181632f3c1dd6e1f5c3  inter-latin-700-normal.woff2
f6734f8177112c0839b961f96d813fcb189d81b60e96c33278c1983b6f419615  roboto-latin-400-normal.woff2
f5aebdfea35d1e7656ef4acc5db1f243209755ae3300943ef8fc6280f363c860  roboto-latin-700-normal.woff2
78e2217793fe46bec6cf1bcabab165033fd94be1935ad1eda2a31ad845073bd6  source-sans-3-latin-400-normal.woff2
37ea272797acc7acda078cd429fe493599f3db0534e2077df5aec0e3c4a23c13  source-sans-3-latin-700-normal.woff2
```

Les fichiers sont déclarés `-text` dans `.gitattributes` pour que ces sommes restent valides.
