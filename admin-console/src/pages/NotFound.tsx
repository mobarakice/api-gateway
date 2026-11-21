import { Link } from 'react-router';

export function NotFound({ what }: { what?: string }) {
  return (
    <section aria-labelledby="nf-title">
      <h1 id="nf-title">Not found</h1>
      <p>{what ? `No ${what}.` : 'This page does not exist.'} <Link to="/">Go to Gateways</Link>.</p>
    </section>
  );
}
