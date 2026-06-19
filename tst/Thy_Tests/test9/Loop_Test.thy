theory Loop_Test
  imports Main
begin

lemma will_loop: "True"
  apply (tactic \<open>fn st => let fun loop (i:int) = (Isabelle_Thread.expose_interrupt (); loop (i + 1)) in loop 0 end\<close>)
  done

lemma needs_filling: "1 + 1 = (2::nat)"
  sorry

end
