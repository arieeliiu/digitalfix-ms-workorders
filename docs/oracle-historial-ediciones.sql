-- Preparación para ejecución manual autorizada: NO se aplica al iniciar el servicio.
-- Revisar propietario, respaldo y checks existentes antes de ejecutar. Oracle DDL confirma implícitamente.
-- Para tablas creadas con oracle-historial.sql. Checks con otros nombres deben revisarse por separado.
DECLARE
    cantidad NUMBER;
    definicion VARCHAR2(4000);
    estado VARCHAR2(20);
    validacion VARCHAR2(20);
BEGIN
    SELECT COUNT(*) INTO cantidad FROM USER_TABLES WHERE TABLE_NAME = 'HISTORIAL_ORDENES';
    IF cantidad = 0 THEN
        RAISE_APPLICATION_ERROR(-20001, 'Crear primero HISTORIAL_ORDENES con oracle-historial.sql');
    END IF;
    SELECT COUNT(*) INTO cantidad FROM USER_CONSTRAINTS
        WHERE TABLE_NAME = 'HISTORIAL_ORDENES' AND CONSTRAINT_NAME = 'CK_HISTORIAL_ORDENES_ACCION_V2';
    IF cantidad = 0 THEN
        -- Se valida la restricción ampliada antes de retirar la anterior.
        EXECUTE IMMEDIATE 'ALTER TABLE historial_ordenes ADD CONSTRAINT ck_historial_ordenes_accion_v2
            CHECK (accion IN (''CREACION'', ''CAMBIO_ESTADO'', ''ELIMINACION'',
                ''EDICION_DATOS'', ''EDICION_CONTACTOS'', ''EDICION_REPUESTOS'')) ENABLE VALIDATE';
    END IF;
    SELECT REGEXP_REPLACE(UPPER(search_condition_vc), '[[:space:]"]', ''), status, validated
        INTO definicion, estado, validacion FROM USER_CONSTRAINTS
        WHERE TABLE_NAME = 'HISTORIAL_ORDENES' AND CONSTRAINT_NAME = 'CK_HISTORIAL_ORDENES_ACCION_V2';
    IF definicion IS NULL
            OR definicion <> 'ACCIONIN(''CREACION'',''CAMBIO_ESTADO'',''ELIMINACION'',''EDICION_DATOS'',''EDICION_CONTACTOS'',''EDICION_REPUESTOS'')'
            OR estado <> 'ENABLED' OR validacion <> 'VALIDATED' THEN
        RAISE_APPLICATION_ERROR(-20002, 'La restricción V2 difiere del contrato; revisar antes de retirar la anterior');
    END IF;
    SELECT COUNT(*) INTO cantidad FROM USER_CONSTRAINTS
        WHERE TABLE_NAME = 'HISTORIAL_ORDENES' AND CONSTRAINT_NAME = 'CK_HISTORIAL_ORDENES_ACCION';
    IF cantidad = 1 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE historial_ordenes DROP CONSTRAINT ck_historial_ordenes_accion';
    END IF;
END;
/

SELECT constraint_name, status, validated, search_condition_vc
FROM user_constraints
WHERE table_name = 'HISTORIAL_ORDENES' AND constraint_type = 'C';
