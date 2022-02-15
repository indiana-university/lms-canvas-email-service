package edu.iu.uits.lms.email.service;

import edu.iu.uits.lms.email.config.EmailServiceConfig;
import edu.iu.uits.lms.email.model.EmailDetails;
import edu.iu.uits.lms.email.model.EmailServiceAttachment;
import edu.iu.uits.lms.email.model.Priority;
import edu.iu.uits.lms.email.model.sis.Attachment;
import edu.iu.uits.lms.email.model.sis.Message;
import edu.iu.uits.lms.email.model.sis.Recipient;
import edu.iu.uits.lms.email.model.sis.Result;
import io.swagger.annotations.Api;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.activation.URLDataSource;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/email")
@Slf4j
@Api(tags = "email")
public class EmailService {

   private static final int SUBJECT_MAX_LENGTH = 500;

   /**
    * this should be roughly the equivalent of 5 MB
    */
   private static final int BODY_MAX_LENGTH = 5242880;


   @Autowired
   private JavaMailSender javaMailSender;

   @Autowired
   private EmailServiceConfig emailServiceConfig;

   @Autowired
   private SignedEmailService signedEmailService;

   @GetMapping("/header")
   @PreAuthorize("#oauth2.hasScope('email:send')")
   public String getStandardHeader() {
      return "[LMS " + emailServiceConfig.getEnv().toUpperCase() + " Notifications]";
   }

   @PostMapping("/send")
   @PreAuthorize("#oauth2.hasScope('email:send')")
   public void sendEmail(@RequestBody EmailDetails emailDetails,
                         @RequestParam(name = "digitallySign", required = false, defaultValue = "true") boolean digitallySign,
                         @RequestParam(name = "unsignedToEmailToUseInPreProd", required = false, defaultValue = "") String unsignedToEmailToUseInPreProd) throws LmsEmailTooBigException, MessagingException {
      String subject = emailDetails.getSubject();
      String body = emailDetails.getBody();
      String[] recipients = emailDetails.getRecipients();
      List<EmailServiceAttachment> emailServiceAttachmentList = emailDetails.getEmailServiceAttachmentList();
      boolean enableHtml = emailDetails.isEnableHtml();
      Priority priority = emailDetails.getPriority();
      String from = emailDetails.getFrom();

      if (from == null) {
         from = emailServiceConfig.getDefaultFrom();
      }

      if (priority == null) {
         priority = Priority.NORMAL;
      }

      if (!emailServiceConfig.isEnabled()) {
         log.info("mail.enabled is false. Logging message\nrecipients: "
               + StringUtils.arrayToCommaDelimitedString(recipients)
               + "\nSubject: " + subject + "\nBody:\n" + body + "\n");
         return;
      }

      if (subject != null && subject.length() > SUBJECT_MAX_LENGTH) {
         subject = subject.substring(0, SUBJECT_MAX_LENGTH);
      }
      if (body != null && body.length() > BODY_MAX_LENGTH) {
         body = body.substring(0, BODY_MAX_LENGTH);
         body += "\nThe message body exceeded " + BODY_MAX_LENGTH + " characters and this message was truncated!";
      }

      int count = 0;
      int maxTries = 3;
      while (true) {
         try {
            Result result = new Result();
            //Only call if signing is enabled
            if (emailServiceConfig.isSigningEnabled()) {
               result = sendSignedEmail(recipients, subject, body, emailServiceAttachmentList, enableHtml, priority, digitallySign, from);
            }
            if (!result.isSuccess() && ++count == maxTries) {
               sendUnsignedEmail(recipients, subject, body, emailServiceAttachmentList, enableHtml, priority, from);
               break;
            } else if (result.isSuccess()) {
               break;
            } else {
               log.warn("Retry attempt #" + count + " for the SIS Email Signing Service");
            }
         } catch (IOException e) {
            log.error("Bad email!", e);
            if (++count == maxTries) {
               if (! "prd".equals(emailServiceConfig.getEnv())) {
                  String preBody = "In production, this message will go to <p />\r\n";

                  for (String recipient : recipients) {
                     preBody += String.format("TO: %s <p />\r\n", recipient);
                  }

                  body = preBody + "\n\n" + body;

                  recipients = new String[] {   unsignedToEmailToUseInPreProd != null &&
                                                unsignedToEmailToUseInPreProd.trim().length() > 0
                                              ? unsignedToEmailToUseInPreProd
                                              : emailServiceConfig.getDefaultUnsignedTo() };

               }

               sendUnsignedEmail(recipients, subject, body, emailServiceAttachmentList, enableHtml, priority, from);
               break;
            }
         }
      }
   }

   /**
    * Send an email using the {@link SignedEmailService}
    * @param recipients List of recipients
    * @param subject Email subject
    * @param body Email body
    * @param emailServiceAttachmentList List of attachments
    * @param enableHtml Flag indicating if it's html or plain text
    * @param priority Priority used to send the message
    * @return {@link Result}
    * @throws IOException Exception is thrown when it is unable to fetch the attachment data
    */
   private Result sendSignedEmail(String[] recipients, String subject, String body,
                                  List<EmailServiceAttachment> emailServiceAttachmentList, boolean enableHtml,
                                  Priority priority, boolean digitallySign, String from) throws IOException {

      List<Recipient> recipientList = new ArrayList<>(recipients.length);
      List<Attachment> attachmentList = new ArrayList<>();
      for (String address : recipients) {
         recipientList.add(new Recipient(Recipient.TYPE.TO, address));
      }

      if (emailServiceAttachmentList != null && !emailServiceAttachmentList.isEmpty()) {
         for (EmailServiceAttachment attachment : emailServiceAttachmentList) {
            if (attachment.getFilename() != null && attachment.getUrl() != null) {
               URLDataSource attachmentDataSource = new URLDataSource(attachment.getUrl());
               log.debug("{} ({})", attachment.getFilename(), attachment.getUrl());
               byte[] bytes = IOUtils.toByteArray(attachment.getUrl());

               Attachment att = new Attachment(Attachment.TYPE.binary, attachmentDataSource.getContentType(),
                     Base64.encodeBase64String(bytes));
               att.setFileName(attachment.getFilename());
               attachmentList.add(att);
            }
         }
      }
      Message message = new Message(from, subject, body, recipientList);
      String messageType = "text/plain";
      if (enableHtml) {
         messageType = "text/html";
      }
      message.setContentType(messageType);

      //Digitally sign?
      if (digitallySign) {
         message.setSignatureAddress("essnorep@iu.edu");
      } else {
         message.setSignatureAddress("donotsign@garbage.foo");
      }

      message.setTestEmailAddress("iu-uits-es-ess-lms-notify@exchange.iu.edu");
      message.setAttach(attachmentList);
      message.setPriority(translatePriority(priority));

      return signedEmailService.postEmail(message);
   }

   /**
    * Send an email using the normal, internal unsigned method
    * @param recipients List of recipients
    * @param subject Email subject
    * @param body Email body
    * @param emailServiceAttachmentList List of attachments
    * @param enableHtml Flag indicating if it's html or plain text
    * @param priority Priority used to send the message
    * @throws LmsEmailTooBigException Exception thrown when the email body is too big to send
    */
   private void sendUnsignedEmail(String[] recipients, String subject, String body, List<EmailServiceAttachment> emailServiceAttachmentList,
                                  boolean enableHtml, Priority priority, String from) throws LmsEmailTooBigException, MessagingException, MailException {
      log.warn("Sending unsigned email");


      MimeMessage message = javaMailSender.createMimeMessage();
      MimeMessageHelper helper = new MimeMessageHelper(message, true);

      for(String email : recipients) {
         helper.addTo(email);
      }

      helper.setFrom(from);

      helper.setText(body, enableHtml);
      helper.setSubject(subject);
      helper.setPriority(priority.getPriority());


      if (emailServiceAttachmentList != null) {
         for (EmailServiceAttachment emailServiceAttachment : emailServiceAttachmentList) {
            if (emailServiceAttachment.getFilename() != null && emailServiceAttachment.getUrl() != null) {
               URLDataSource attachmentDataSource = new URLDataSource(emailServiceAttachment.getUrl());
               helper.addAttachment(emailServiceAttachment.getFilename(), attachmentDataSource);
            }
         }
      }

      if (message.getSize() > BODY_MAX_LENGTH) {
         throw new LmsEmailTooBigException();
      }

      javaMailSender.send(message);

   }

   /**
    * Translate a {@link Message.PRIORITY} into a {@link Message.PRIORITY}
    * @param priority Input priority
    * @return Translated priority
    */
   private Message.PRIORITY translatePriority(Priority priority) {
      Message.PRIORITY result;
      switch (priority) {
         case LOW:
            result = Message.PRIORITY.LOW;
            break;
         case HIGH:
            result = Message.PRIORITY.HIGH;
            break;
         default:
            result = Message.PRIORITY.NORMAL;
            break;
      }
      return result;
   }
}
