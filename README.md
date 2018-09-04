# Starfish Export
A Quartz job for exporting gradebook and assignment data from Sakai to a series of CSV files.

## Building
```
mvn clean install sakai:deploy -Dmaven.tomcat.home=/path/to/tomcat
```

## Configuring

In ``sakai.properties`` set the following options:

The path where the exported CSV files will be saved:
```
starfish.export.path=/mnt/starfish/export
```

The sites matching this academic term will be exported. Leave this blank to use the most current active term
```
gradebook.export.term=FA18
```

---
