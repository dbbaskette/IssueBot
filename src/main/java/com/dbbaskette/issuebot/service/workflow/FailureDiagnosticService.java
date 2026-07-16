package com.dbbaskette.issuebot.service.workflow;
import com.dbbaskette.issuebot.model.*;
import com.dbbaskette.issuebot.repository.*;
import com.dbbaskette.issuebot.security.LogSanitizer;
import org.springframework.stereotype.Service;
import java.util.Optional;
@Service public class FailureDiagnosticService {
 private final FailureDiagnosticRepository diagnostics; private final TrackedIssueRepository issues;
 public FailureDiagnosticService(FailureDiagnosticRepository d,TrackedIssueRepository i){diagnostics=d;issues=i;}
 public FailureDiagnostic record(TrackedIssue i,FailureCategory c,String s,String p,String d,String a,FailureRetryability r){
  String summary=limit(LogSanitizer.sanitize(s),1000); String details=limit(LogSanitizer.sanitize(d),8000); String action=limit(LogSanitizer.sanitize(a),1000);
  FailureDiagnostic saved=diagnostics.save(new FailureDiagnostic(i,c,summary,p,details,action,r)); i.setLastFailureReason(summary); issues.save(i); return saved;
 }
 public Optional<FailureDiagnostic> latestFor(TrackedIssue i){return diagnostics.findFirstByIssueOrderByOccurredAtDesc(i);}
 private static String limit(String s,int n){return s!=null&&s.length()>n?s.substring(0,n):s;}
}
