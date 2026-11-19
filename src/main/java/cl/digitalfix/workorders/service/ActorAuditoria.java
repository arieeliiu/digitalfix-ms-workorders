package cl.digitalfix.workorders.service;

// Quién ejecuta una acción, tal como queda en el historial.
// id = oid de Entra; roles = roles de la aplicación separados por coma (puede ser null).
public record ActorAuditoria(String id, String roles) {}
