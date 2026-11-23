export default function Placeholder({ title, bf }: { title: string; bf: string }) {
  return (
    <section>
      <h1>{title}</h1>
      <p>Écran à développer ({bf}).</p>
    </section>
  );
}
