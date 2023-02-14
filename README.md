# Prueba
## Sobre arquitectura web
Prueba

## Iniciando

Siga las siguientes instrucciones para iniciar el desarrollo de este proyecto.

### Pre-Requisitos

Plugins que deben estar instalados en su IDE:
* [Lombok](http://projectlombok.org/) - *Libreria de Bytecode que genera automaticamente los Getters y Setters*.
* [CheckStyle](http://www.checkstyle.com/) - *Plugin para poder comprobar el estilo del codigo usando las reglas de Google*
* FindBugs - *Plugin que realiza un análisis estático para buscar errores en el código en base a patrones de errores.* 

---
Instalar JCE (Java Cryptography Extension)

* [JCE](http://www.oracle.com/technetwork/java/javase/downloads/jce8-download-2133166.html)


## Flujo de desarrollo.

* Todo desarrollo debe iniciarse en una rama con la nomenclatura `feature/nombre-de-cambio` el cual debe crearse desde la rama `develop`.

* Cuando se completa el desarrollo, se deberá generar un `New Merge Request` desde la rama creada `feature/nombre-de-cambio` hacia la rama `develop`.

* Cuando los cambios son revisados y probados, se aceptará el Merge Request, con lo que los cambios quedarán listos en la rama `develop` para realizar el despliegue en el ambiente de desarrollo.

## Generacion de codigo
Para el caso de una arquitectura web, para poder aprovechar el codigo generado a partir del contrato se debe ejecutar lo siguiente:
```
mvn clean generate-sources
```

## Ejecución de pruebas

Para la ejecución de pruebas `unitarias` se debe ejecutar lo siguiente:

```
mvn test
```

Para la ejecución de pruebas de `integración` se debe ejecutar lo siguiente:

```
mvn verify -Dskip.integration.tests=false -Dskip.unit.tests=true
```

## Despliegue

Los despliegues se realizan desde [Jenkins].

Los pipeline para realizar los despliegues se encuentran en los siguientes archivos:

* `jenkins/Jenkinsfile-delivery-dev-pipeline.groovy` el cual compila, ejecuta las pruebas unitarias, hace análisis con SonarQube, sube la versión SNAPSHOT del proyecto a artifactory y despliega en el ambiente de desarrollo.
* `jenkins/Jenkinsfile-delivery-cert-pipeline.groovy` el cual crea una nueva rama `release/{version}` desde la ranma `develop`, crea un tag `RC`, sube el proyecto dentro de CERT en artifactory y despliega en el ambiente de certificación.
* `jenkins/Jenkinsfile-delivery-prod-pipeline.groovy` el cual despliega al ambiente de producción en base al tag `RC` y hace merge de la rama `release/{version}` a `master`.

Los servidores donde se desplegará el proyecto se definen en `devops/ansible/hosts`.

Las variables de entorno para cada ambiente son definidos en los siguientes archivos:

* `devops/deploy/dev-vars.yml` para desarrollo.
* `devops/deploy/cert-vars.yml` para certificación.
* `devops/deploy/prod-vars.yml` para producción.
