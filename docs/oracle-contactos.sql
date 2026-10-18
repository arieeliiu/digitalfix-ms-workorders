-- Migración aditiva para Oracle. Ejecutar con el propietario del esquema.
-- No requiere valores iniciales y conserva las órdenes existentes.
-- Idempotente si Hibernate ddl-auto=update ya creó alguna columna.
DECLARE
    cantidad NUMBER;
BEGIN
    SELECT COUNT(*) INTO cantidad FROM USER_TAB_COLUMNS
      WHERE TABLE_NAME = 'ORDENES_TRABAJO' AND COLUMN_NAME = 'EMAIL_CONTACTO';
    IF cantidad = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE ordenes_trabajo ADD (email_contacto VARCHAR2(254 CHAR))';
    END IF;
    SELECT COUNT(*) INTO cantidad FROM USER_TAB_COLUMNS
      WHERE TABLE_NAME = 'ORDENES_TRABAJO' AND COLUMN_NAME = 'EMAIL_TECNICO';
    IF cantidad = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE ordenes_trabajo ADD (email_tecnico VARCHAR2(254 CHAR))';
    END IF;
END;
/
