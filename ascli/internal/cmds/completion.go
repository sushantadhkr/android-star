package cmds

import (
	"encoding/json"
	"fmt"
	"strings"

	"github.com/sushantadhkr/android-star/ascli/internal/client"
)

// Completion is the shell-completion script.
//
// Built from the same generated table as the commands, so what the shell offers and what ascli
// accepts cannot come apart — a completion that suggests an action which does not exist is worse
// than none, because it teaches the wrong thing.
func Completion(shell string) (string, error) {
	commands := GeneratedCommands()

	var featureNames []string
	seen := map[string]bool{}
	for _, cmd := range commands {
		feature := strings.SplitN(cmd.Path, " ", 2)[0]
		if !seen[feature] {
			seen[feature] = true
			featureNames = append(featureNames, feature)
		}
	}

	switch shell {
	case "bash":
		return bashCompletion(featureNames, commands), nil
	case "zsh":
		return zshCompletion(featureNames, commands), nil
	case "fish":
		return fishCompletion(featureNames, commands), nil
	case "powershell":
		return powershellCompletion(featureNames, commands), nil
	default:
		return "", &client.Error{
			Status:  400,
			Message: fmt.Sprintf("No completion for '%s'.", shell),
			Hint:    "Use bash, zsh, fish or powershell.",
		}
	}
}

func fixedCommands() []string {
	return []string{
		"connect", "doctor", "capabilities", "capability", "platform",
		"invoke", "pull", "raw", "completion", "help",
	}
}

func allFlags() []string {
	return []string{"--json", "--verbose", "--token", "--url", "--tls-skip-verify", "--help", "--version", "--output", "--watch", "--until", "--body", "--no-save", "--token-stdin"}
}

func bashCompletion(features []string, commands []GeneratedCommand) string {
	var b strings.Builder
	b.WriteString("# ascli bash completion. Add to ~/.bashrc:  source <(ascli completion bash)\n")
	b.WriteString("_ascli() {\n")
	b.WriteString("  local cur prev\n")
	b.WriteString("  cur=\"${COMP_WORDS[COMP_CWORD]}\"\n")
	b.WriteString("  prev=\"${COMP_WORDS[COMP_CWORD-1]}\"\n\n")

	// Flags offered after a known feature-and-action, taken from what that action documents.
	b.WriteString("  if [ \"$COMP_CWORD\" -ge 3 ]; then\n")
	b.WriteString("    local path=\"${COMP_WORDS[1]} ${COMP_WORDS[2]}\"\n")
	b.WriteString("    case \"$path\" in\n")
	for _, cmd := range commands {
		if len(cmd.Params) == 0 {
			continue
		}
		flags := make([]string, 0, len(cmd.Params)+len(allFlags()))
		for _, p := range cmd.Params {
			flags = append(flags, "--"+p.Name)
		}
		flags = append(flags, allFlags()...)
		fmt.Fprintf(&b, "      %q) COMPREPLY=( $(compgen -W %q -- \"$cur\") ); return ;;\n",
			cmd.Path, strings.Join(flags, " "))
	}
	b.WriteString("    esac\n")
	b.WriteString("  fi\n\n")

	// Flags offered after a known feature but before its action is chosen.
	b.WriteString("  if [ \"$COMP_CWORD\" -eq 2 ]; then\n")
	b.WriteString("    local actions=\"\"\n")
	b.WriteString("    case \"${COMP_WORDS[1]}\" in\n")
	byFeature := map[string][]string{}
	for _, cmd := range commands {
		parts := strings.SplitN(cmd.Path, " ", 2)
		if len(parts) == 2 {
			byFeature[parts[0]] = append(byFeature[parts[0]], parts[1])
		}
	}
	for feature, actions := range byFeature {
		fmt.Fprintf(&b, "      %q) actions=%q ;;\n", feature, strings.Join(actions, " "))
	}
	b.WriteString("    esac\n")
	b.WriteString("    COMPREPLY=( $(compgen -W \"$actions\" -- \"$cur\") )\n")
	b.WriteString("    return\n")
	b.WriteString("  fi\n\n")

	b.WriteString("  COMPREPLY=( $(compgen -W \"" +
		strings.Join(append(fixedCommands(), features...), " ") +
		"\" -- \"$cur\") )\n")
	b.WriteString("}\ncomplete -F _ascli ascli\n")
	return b.String()
}

func zshCompletion(features []string, commands []GeneratedCommand) string {
	var b strings.Builder
	b.WriteString("#compdef ascli\n")
	b.WriteString("# ascli zsh completion. Add to ~/.zshrc:  ascli completion zsh > \"${fpath[1]}/_ascli\"\n")
	b.WriteString("_ascli() {\n")
	b.WriteString("  local -a features actions flags\n")
	fmt.Fprintf(&b, "  features=(%s)\n", shellJoin(append(fixedCommands(), features...)))

	byFeature := map[string][]string{}
	byPath := map[string][]string{}
	for _, cmd := range commands {
		parts := strings.SplitN(cmd.Path, " ", 2)
		if len(parts) != 2 {
			continue
		}
		byFeature[parts[0]] = append(byFeature[parts[0]], parts[1])
		flags := []string{}
		for _, p := range cmd.Params {
			flags = append(flags, "--"+p.Name)
		}
		byPath[cmd.Path] = append(flags, allFlags()...)
	}

	b.WriteString("  if (( CURRENT == 2 )); then\n")
	b.WriteString("    _describe 'command' features\n")
	b.WriteString("    return\n")
	b.WriteString("  fi\n")
	b.WriteString("  local feature=${words[2]}\n")
	b.WriteString("  if (( CURRENT == 3 )); then\n")
	b.WriteString("    actions=(${=${(f)\"$(print -l -- ${(k)actions_map}\")${(j: :)actions_map[$feature]}}})\n")
	b.WriteString("    actions=(${(s: :)actions_map[$feature]})\n")
	b.WriteString("    _describe 'action' actions\n")
	b.WriteString("    return\n")
	b.WriteString("  fi\n")
	b.WriteString("  flags=(${(s: :)flags_map[\"$feature ${words[3]}\"]})\n")
	b.WriteString("  _describe 'flag' flags\n")
	b.WriteString("}\n\n")

	b.WriteString("typeset -Ag actions_map flags_map\n")
	for feature, actions := range byFeature {
		fmt.Fprintf(&b, "actions_map[%q]=%q\n", feature, strings.Join(actions, " "))
	}
	for path, flags := range byPath {
		fmt.Fprintf(&b, "flags_map[%q]=%q\n", path, strings.Join(flags, " "))
	}
	b.WriteString("\n_ascli \"$@\"\n")
	return b.String()
}

func fishCompletion(features []string, commands []GeneratedCommand) string {
	var b strings.Builder
	b.WriteString("# ascli fish completion. Add to ~/.config/fish/config.fish:\n")
	b.WriteString("#   ascli completion fish | source\n")

	for _, cmd := range fixedCommands() {
		fmt.Fprintf(&b, "complete -c ascli -n '__fish_use_subcommand' -f -a %q\n", cmd)
	}
	for _, feature := range features {
		fmt.Fprintf(&b, "complete -c ascli -n '__fish_use_subcommand' -f -a %q\n", feature)
	}

	byFeature := map[string][]GeneratedCommand{}
	for _, cmd := range commands {
		parts := strings.SplitN(cmd.Path, " ", 2)
		if len(parts) == 2 {
			byFeature[parts[0]] = append(byFeature[parts[0]], cmd)
		}
	}
	for feature, cmds := range byFeature {
		for _, cmd := range cmds {
			parts := strings.SplitN(cmd.Path, " ", 2)
			var flags strings.Builder
			for _, p := range cmd.Params {
				fmt.Fprintf(&flags, " -l %s -d %q", p.Name, p.Description)
			}
			fmt.Fprintf(&b, "complete -c ascli -n '__fish_seen_subcommand_from %s' -f -a %q%s\n",
				feature, parts[1], flags.String())
		}
	}
	for _, flag := range allFlags() {
		name := strings.TrimLeft(flag, "-")
		fmt.Fprintf(&b, "complete -c ascli -l %s\n", name)
	}
	return b.String()
}

func powershellCompletion(features []string, commands []GeneratedCommand) string {
	var b strings.Builder
	b.WriteString("# ascli PowerShell completion.\n")
	b.WriteString("Register-ArgumentCompleter -Native -CommandName ascli -ScriptBlock {\n")
	b.WriteString("  param($wordToComplete, $commandAst, $cursorPosition)\n\n")
	b.WriteString("  $elements = $commandAst.CommandElements | Select-Object -Skip 1 | ForEach-Object { $_.ToString() }\n")
	b.WriteString("  $fixed = @(" + quoteJoin(fixedCommands()) + ")\n")
	fmt.Fprintf(&b, "  $features = @(%s)\n", quoteJoin(features))

	b.WriteString("  if ($elements.Count -le 1) {\n")
	b.WriteString("    @($fixed + $features) | Where-Object { $_ -like \"$wordToComplete*\" } | ForEach-Object {\n")
	b.WriteString("      [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_)\n")
	b.WriteString("    }\n")
	b.WriteString("    return\n")
	b.WriteString("  }\n\n")

	byFeature := map[string][]string{}
	for _, cmd := range commands {
		parts := strings.SplitN(cmd.Path, " ", 2)
		if len(parts) == 2 {
			byFeature[parts[0]] = append(byFeature[parts[0]], parts[1])
		}
	}
	b.WriteString("  if ($elements.Count -eq 2) {\n")
	b.WriteString("    $map = @{\n")
	for feature, actions := range byFeature {
		fmt.Fprintf(&b, "      '%s' = @(%s)\n", feature, quoteJoin(actions))
	}
	b.WriteString("    }\n")
	b.WriteString("    $feature = $elements[0]\n")
	b.WriteString("    if ($map.ContainsKey($feature)) {\n")
	b.WriteString("      $map[$feature] | Where-Object { $_ -like \"$wordToComplete*\" } | ForEach-Object {\n")
	b.WriteString("        [System.Management.Automation.CompletionResult]::new($_, $_, 'ParameterValue', $_)\n")
	b.WriteString("      }\n")
	b.WriteString("    }\n")
	b.WriteString("    return\n")
	b.WriteString("  }\n")
	b.WriteString("  [System.Management.Automation.CompletionResult]::new($wordToComplete, $wordToComplete, 'ParameterValue', '')\n")
	b.WriteString("}\n")
	return b.String()
}

func quoteJoin(items []string) string {
	quoted := make([]string, 0, len(items))
	for _, item := range items {
		encoded, _ := json.Marshal(item)
		quoted = append(quoted, string(encoded))
	}
	return strings.Join(quoted, ", ")
}

func shellJoin(items []string) string {
	quoted := make([]string, 0, len(items))
	for _, item := range items {
		quoted = append(quoted, "'"+strings.ReplaceAll(item, "'", `'\''`)+"'")
	}
	return strings.Join(quoted, " ")
}
