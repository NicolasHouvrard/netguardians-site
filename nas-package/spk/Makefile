# Paquet DSM "site-nas" -- variables nommees selon les conventions spksrc
# (spk/<paquet>/Makefile : SPK_NAME, SPK_VERS, SPK_REV, MAINTAINER, DESCRIPTION,
# DISPLAY_NAME, SPK_DEPENDS, WIZARDS_DIR...).
#
# ADAPTATION DELIBEREE (voir README.md, "Construction") : ce Makefile n'inclut PAS
# ../../mk/spksrc.spk.mk. Le paquet ne compile rien (scripts shell + jar + JRE
# deja construits ailleurs + images Docker tirees de GHCR, comme l'exemple Docker
# officiel Synology) ; l'empaquetage est fait par build-spk.sh avec GNU tar en
# format POSIX ustar, sans la chaine spksrc complete (non installable dans
# l'environnement ou ce paquet a ete ecrit). Les noms de variables restent ceux
# de spksrc pour qu'un portage vers un vrai arbre spksrc soit mecanique.
#
# Usage (une architecture par appel ; le JRE embarque est propre a l'architecture) :
#   make spk SPK_ARCH=x86_64 JRE_DIR=/chemin/jre-x64 AGENT_JAR=/chemin/agent.jar \
#            HEADSCALE_URL=https://... IMAGE_OWNER=... IMAGE_TAG=...
#   make spk SPK_ARCH=armv8  JRE_DIR=/chemin/jre-aarch64 ...
#   make all-archs JRE_DIR_x86_64=... JRE_DIR_armv8=... (autres variables idem)

SPK_NAME = site-nas
SPK_VERS = 1.0.0
SPK_REV = 1
DISPLAY_NAME = Site NAS
MAINTAINER = logicielpourtous
DESCRIPTION = Amorce ce NAS dans le reseau Headscale du projet : Agent de deploiement bleu-vert, Postgres, Nginx local et premiere instance de l'application, sur des ports non standard.
# DSM 7.2 minimum : le paquet Docker s'appelle "ContainerManager" depuis 7.2.
OS_MIN_VER = 7.2-64561
SPK_DEPENDS = ContainerManager:Tailscale
WIZARDS_DIR = src/wizard
# Familles d'architecture DSM (guide developpeur, annexe A) : x86_64 et armv8.
SUPPORTED_ARCHS = x86_64 armv8

SPK_ARCH ?= x86_64
SORTIE ?= dist

# Entrees obligatoires, sans valeur par defaut devinee (meme discipline que
# app.deploiement.docker-image-owner cote Agent).
JRE_DIR ?=
AGENT_JAR ?=
HEADSCALE_URL ?=
IMAGE_OWNER ?=
IMAGE_TAG ?=

export SPK_NAME SPK_VERS SPK_REV DISPLAY_NAME MAINTAINER DESCRIPTION OS_MIN_VER \
       SPK_DEPENDS WIZARDS_DIR SPK_ARCH SORTIE JRE_DIR AGENT_JAR HEADSCALE_URL \
       IMAGE_OWNER IMAGE_TAG

.PHONY: spk all-archs clean

spk:
	./build-spk.sh

all-archs:
	$(foreach arch,$(SUPPORTED_ARCHS),$(MAKE) spk SPK_ARCH=$(arch) JRE_DIR="$(JRE_DIR_$(arch))" &&) true

clean:
	rm -rf build $(SORTIE)
