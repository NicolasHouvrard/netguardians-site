# Image runtime de l'application. Le .jar est construit AVANT (workflow GitHub Actions,
# voir .github/workflows/publier-image.yml) puis copie ici : l'etape lente (Maven) tourne
# une seule fois sur le runner natif, et buildx n'emule l'architecture ARM que pour cette
# image minimale.
#
# Aucune configuration secrete dans l'image : application.properties / -prod sont
# generes depuis leurs fichiers .example (placeholders), tout le reste vient de
# l'environnement ou de /run/secrets-app (configtree) au demarrage du conteneur.
FROM eclipse-temurin:25-jre

RUN groupadd --system --gid 10001 site \
 && useradd  --system --uid 10001 --gid site --home-dir /app --shell /usr/sbin/nologin site \
 && mkdir -p /app/uploads \
 && chown -R site:site /app

WORKDIR /app
COPY --chown=site:site target/site-*.jar /app/app.jar

USER site
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
