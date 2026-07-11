package com.meogo.infra.persistence.scan

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.persistence.autoconfigure.EntityScan

@SpringBootApplication
@EntityScan(basePackages = ["com.meogo.infra.persistence.scan", "com.meogo.infra.persistence.food"])
class ScanPersistenceTestApp
