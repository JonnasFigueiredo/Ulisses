/**
 * Marca do Ulisses: elmo corintio (capacete grego de heroi) com crista.
 * Desenhado em currentColor para herdar a cor do contexto (branco sobre o brand-mark roxo).
 */
export default function HelmetMark({ size = 26, className }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="128 48 768 768"
      fill="currentColor"
      className={className}
      aria-hidden="true"
    >
      <path
        fillRule="evenodd"
        d="M512 250 C652 255 735 335 743 470 C748 590 728 680 690 752
           C675 780 640 782 620 752 C600 706 596 648 576 620
           C556 592 535 585 512 583 C489 585 468 592 448 620
           C428 648 424 706 404 752 C384 782 349 780 334 752
           C296 680 276 590 281 470 C289 335 372 255 512 250 Z
           M370 508 C401 478 463 478 494 508 C463 538 401 538 370 508 Z
           M530 508 C561 478 623 478 654 508 C623 538 561 538 530 508 Z
           M476 600 C490 585 534 585 548 600 L548 690 C534 705 490 705 476 690 Z
           M482 170 L542 170 L548 262 L476 262 Z
           M424 208 C420 108 468 82 512 82 C556 82 604 108 600 208
           C566 178 458 178 424 208 Z"
      />
    </svg>
  );
}
