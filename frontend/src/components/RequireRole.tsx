import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import type { Role } from '../api/types';
import { hasAny } from '../auth/roles';

/** Confort d'affichage uniquement : le serveur applique les vraies règles (CdC §9.5, F1). */
export default function RequireRole({ roles }: { roles?: Role[] }) {
  const { user } = useAuth();
  const loc = useLocation();
  if (!user) return <Navigate to="/login" replace state={{ from: loc.pathname }} />;
  if (!hasAny(user, roles)) return <Navigate to="/" replace />;
  return <Outlet />;
}
