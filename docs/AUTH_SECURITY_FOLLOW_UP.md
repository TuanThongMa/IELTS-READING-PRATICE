# Authentication: deferred work and launch review

The current priority is to complete the core features and system framework in small, testable frontend/backend/database slices. This is a personal and academic project, so complexity and delivery time are legitimate trade-offs. The items below are tracked, not implicitly approved for immediate implementation.

## Review before any public launch

- [ ] Add appropriate login attempt throttling and abuse protection; consider registration abuse and account-enumeration risks, including the current duplicate-email response.
- [ ] Deploy over HTTPS, explicitly configure secure session cookies in the deployed environment, and verify the cookie and CSRF behavior on the actual deployment.
- [ ] Enforce account activation and role-based authorization consistently on protected operations; verify what happens to existing sessions when an account is disabled.
- [ ] Test authentication, authorization, CSRF, logout, and common failure cases end-to-end in the deployment environment; review dependencies and avoid exposing secrets in logs.
- [ ] Decide how sessions will be stored and invalidated across restarts or multiple instances. The current in-memory approach is a simplicity trade-off, not a persistent-session solution.

## Product decisions to schedule

- [ ] Decide whether email verification and password reset are required for the intended first users; implement safe recovery before relying on accounts that cannot otherwise be recovered.
- [ ] Reassess duplicate-email feedback against privacy and usability needs; it currently reveals whether an email is registered.

These items do not mean the current authentication is ready for public deployment. Review their priority and any accepted residual risks together before launch; update this list as decisions and implementations are made.
