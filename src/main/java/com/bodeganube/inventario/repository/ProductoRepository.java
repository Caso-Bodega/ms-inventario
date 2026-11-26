package com.bodeganube.inventario.repository;

import com.bodeganube.inventario.model.Producto;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ProductoRepository extends JpaRepository<Producto, Long> {

    Optional<Producto> findBySku(String sku);

    /**
     * Lee el producto bloqueando su fila hasta el fin de la transaccion (SELECT ... FOR UPDATE).
     * Si llegan varias reservas del mismo SKU al mismo tiempo (varias replicas de ms-ordenes o varias Lambdas),
     * se atienden de a una: ninguna pisa el stock que calculo otra (evita la sobreventa).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Producto p where p.sku = :sku")
    Optional<Producto> findBySkuParaActualizar(String sku);

    boolean existsBySku(String sku);
}
