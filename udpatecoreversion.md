# org.hl7.fhir.core

matchbox depends on org.hl7.fhir.core. the project is available on [github](https://github.com/hapifhir/org.hl7.fhir.core) and does frequent releases https://github.com/hapifhir/org.hl7.fhir.core/releases which have to be integrated in matchbox.

matchbox has patched a few classes, that's why an update cannot be done just through referencing mvn.

the following steps need to be performed:

## sync the forked project to the latest release https://github.com/hapifhir/org.hl7.fhir.core/releases and checkout branch

```
cd ../org.hl7.fhir.core/
git checkout master
git fetch upstream
git merge upstream/master
git push
```
check out the latest release e.g

```
git checkout -b oe_$(git describe --tags --abbrev=0)
cd ../matchbox
```


##  upgrade matchbox to latest version

1. in all the dependent pom.xml the core version needs to be updated to the latest core version

2.  there is a shell script ./updatehapi.sh which copies from the core directory the files which need to be modified

```
./updatehapi.sh
```

now all the patches are lost and need to be added again, they are marked with `matchbox patch'



